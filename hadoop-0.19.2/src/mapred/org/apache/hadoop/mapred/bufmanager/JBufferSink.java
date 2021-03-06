package org.apache.hadoop.mapred.bufmanager;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.ChecksumException;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.DataInputBuffer;
import org.apache.hadoop.io.compress.CompressionCodec;
import org.apache.hadoop.io.compress.DefaultCodec;
import org.apache.hadoop.mapred.IFile;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.FileHandle;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.Task;
import org.apache.hadoop.mapred.TaskAttemptID;
import org.apache.hadoop.mapred.TaskID;
import org.apache.hadoop.util.ReflectionUtils;


public class JBufferSink<K extends Object, V extends Object> {
	private static final Log LOG = LogFactory.getLog(JBufferSink.class.getName());
	  
	private class SpillRun {
		TaskID taskid;
		
		float progress;
		
		Path data;
		
		Path index;
		
		public SpillRun(TaskID taskid, float progress, Path data, Path index) {
			this.taskid = taskid;
			this.progress = progress;
			this.data = data;
			this.index = index;
		}
		
	}
	
	private Reporter reporter;
	
	private JobConf conf;
	
	private TaskAttemptID ownerid;
	
	private int maxConnections;
	
	private Executor executor;
	
	private FileSystem localFs;
	
	private Thread acceptor;
	
	private ServerSocketChannel server;
	
	private int numInputs;
	
	private JBufferCollector<K, V> collector;
	
	private SnapshotManager snapshot;
	
	private Set<Connection> connections;
	
	private Map<TaskID, Float> inputProgress;
	
	private Thread spillThread;
	
	private BlockingQueue<SpillRun> spillQueue;
	
	private Set<TaskID> successful;
	
	private Task task;
	
	private FileHandle fileHandle;
	
	private Class<K> inputKeyClass;
	
	private Class<V> inputValClass;
	
	private CompressionCodec codec;
	
	public JBufferSink(JobConf conf, 
			           Reporter reporter, 
			           JBufferCollector<K, V> collector, 
			           boolean inputSnapshots, 
			           Task task,
			           Class<K> inputKeyClass, Class<V> inputValClass,
			           Class<? extends CompressionCodec> inputCodecClass) 
	throws IOException {
		this.conf = conf;
		this.reporter = reporter;
		this.ownerid = task.getTaskID();
		this.collector = collector;
		this.snapshot = inputSnapshots ? 
				new SnapshotManager(conf, task, inputKeyClass, inputValClass, inputCodecClass) : null;
		this.localFs = FileSystem.getLocal(conf);
		this.maxConnections = conf.getInt("mapred.reduce.parallel.copies", 20);
		this.fileHandle = new FileHandle(ownerid.getJobID());
		this.fileHandle.setConf(conf);
		this.spillQueue = new LinkedBlockingQueue<SpillRun>();
		
		this.inputKeyClass = inputKeyClass;
		this.inputValClass = inputValClass;
		this.codec = inputCodecClass == null ? null :
			(CompressionCodec) ReflectionUtils.newInstance(inputCodecClass, conf);

		
		this.task = task;
	    this.numInputs = task.getNumberOfInputs();
		this.executor = Executors.newFixedThreadPool(Math.min(maxConnections, Math.max(numInputs, 5)));
		this.connections = new HashSet<Connection>();
		this.successful = new HashSet<TaskID>();
		this.inputProgress = new HashMap<TaskID, Float>();
		
		/** The server socket and selector registration */
		this.server = ServerSocketChannel.open();
		this.server.configureBlocking(true);
		this.server.socket().bind(new InetSocketAddress(0));
	}
	
	public InetSocketAddress getAddress() {
		try {
			String host = InetAddress.getLocalHost().getCanonicalHostName();
			return new InetSocketAddress(host, this.server.socket().getLocalPort());
		} catch (UnknownHostException e) {
			return new InetSocketAddress("localhost", this.server.socket().getLocalPort());
		}
	}
	
	public SnapshotManager snapshotManager() {
		return this.snapshot;
	}
	
	public void open() {
		this.acceptor = new Thread() {
			public void run() {
				try {
					BufferRequestResponse response = new BufferRequestResponse();
					while (server.isOpen()) {
						SocketChannel channel = server.accept();
						channel.configureBlocking(true);
						DataInputStream  input  = new DataInputStream(new BufferedInputStream(channel.socket().getInputStream()));
						Connection       conn   = new Connection(input, conf);

						DataOutputStream output = new DataOutputStream(new BufferedOutputStream(channel.socket().getOutputStream()));
						response.reset();

						if (complete()) {
							LOG.debug("JBufferSink: " + ownerid + " is complete. Terminating " + channel.socket().getRemoteSocketAddress());
							response.setTerminated();
							response.write(output);
							output.flush();
							conn.close();
						}
						else if (connections.size() > maxConnections) {
							LOG.debug("JBufferSink: " + ownerid + " max connections reached. Terminating " + channel.socket().getRemoteSocketAddress());
							response.setRetry();
							response.write(output);
							output.flush();
							conn.close();
						}
						else {
							try {
								response.setOpen();
								response.write(output);
								output.flush();
								executor.execute(conn);
								connections.add(conn);
								LOG.debug("JBufferSink: " + ownerid + " accepted connection " + channel.socket().getRemoteSocketAddress());
							} catch (Throwable t) {
								LOG.warn("Received error when trying to execute connection. " + t);
								conn.close();
							}
						}
					}
					LOG.info("JBufferSink " + ownerid + " buffer response server closed.");
				} catch (IOException e) {  }
			}
		};
		acceptor.setDaemon(true);
		acceptor.setPriority(Thread.MAX_PRIORITY);
		acceptor.start();
		
		spillThread = new Thread() {
			@Override
			public void interrupt() {
				while (spillQueue.size() > 0) {
					drain();
				}
				super.interrupt();
			}
			
			private void drain() {
				synchronized (task) {
					List<SpillRun> runs = new ArrayList<SpillRun>();
					spillQueue.drainTo(runs);
					long timestamp = System.currentTimeMillis();
					LOG.debug("JBufferSink begin drain spill runs.");
					for (SpillRun run : runs) {
						try { 
							if (getProgress(run.taskid) < run.progress) {
								collector.spill(run.data, run.index, JBufferCollector.SpillOp.COPY);
								updateProgress(run.taskid, run.progress);
							}
						} catch (IOException e) {
							e.printStackTrace();
						}
					}
					LOG.debug("JBufferSink end drain spills. total time = " + 
							  (System.currentTimeMillis() - timestamp) + " ms.");
				}
			}
			
			public void run() {
				try {
					while (!isInterrupted()) {
						try {
							SpillRun run = spillQueue.take();
							spillQueue.add(run);
							drain();
						} catch (InterruptedException e) {
							return;
						}
					}
				} finally {
					LOG.info("JBufferSink " + ownerid + " spill thread exit.");
				}
			}
		};
		spillThread.setDaemon(true);
		spillThread.start();
	}
	
	public TaskAttemptID ownerID() {
		return this.ownerid;
	}
	
	/**
	 * Close sink.
	 * No more connections can be made once closed. Method will
	 * lock the owning task object if snapshots are turned on.
	 * @return true if the complete snapshot of all input snapshots were
	 * applied to the buffer.
	 * @throws IOException 
	 */
	public synchronized void close() throws IOException {
		LOG.info("JBufferSink is closing.");
		if (this.acceptor == null) return; // Already done.
		try {
			this.acceptor.interrupt();
			this.server.close();
			this.acceptor = null;
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		this.spillThread.interrupt();
	}
	
	private void spill(TaskID taskid, float progress, Path data, Path index) {
		spillQueue.add(new SpillRun(taskid, progress, data, index));
	}
	
	private JBufferCollector<K, V> buffer() {
		return this.collector;
	}
	
	public boolean complete() {
		return this.successful.size() == numInputs;
	}
	
	public void cancel(TaskAttemptID attempt) {
		// TODO 
	}
	
	private void done(Connection connection) {
		this.connections.remove(connection);
	}
	
	private float getProgress(TaskID taskid) {
		return this.inputProgress.containsKey(taskid) ? 
				this.inputProgress.get(taskid) : 0f;
	}
	
	private void updateProgress(TaskID taskid, float progress) {
		if (progress == 1f) {
			this.successful.add(taskid);
		}
		if (!this.inputProgress.containsKey(taskid) ||
				this.inputProgress.get(taskid) < progress) {
			this.inputProgress.put(taskid, progress);
			float progressSum = 0f;
			for (Float f : this.inputProgress.values()) {
				progressSum += f;
			}
			
			collector.getProgress().set(progressSum / (float) numInputs);
			reporter.progress();
		}
		
		synchronized (task) {
			task.notifyAll();
		}
	}
	
	/************************************** CONNECTION CLASS **************************************/
	
	private class Connection implements Runnable {
		private DataInputStream input;
		
		private boolean open;
		
		private int spills;
		
		public Connection(DataInputStream input, JobConf conf) throws IOException {
			this.input = input;
			this.open = true;
			this.spills = 0;
		}
		
		public void close() {
			synchronized (this) {
				open = false;
				if (this.input == null) return;
				try {
					this.input.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
				finally {
					this.input = null;
				}
			}
		}
		
		private void 
		spill(TaskID taskid, float progress, IFile.Reader<K, V> reader, long length) 
		throws IOException {
			// Spill directory to disk
			int spillid = spills++;
			Path filename      = fileHandle.getInputSpillFileForWrite(ownerid, taskid, spillid, length);
			Path indexFilename = fileHandle.getInputSpillIndexFileForWrite(ownerid, taskid, spillid, JBuffer.MAP_OUTPUT_INDEX_RECORD_LENGTH);

			FSDataOutputStream out      = localFs.create(filename, false);
			FSDataOutputStream indexOut = localFs.create(indexFilename, false);

			if (out == null || indexOut == null) 
				throw new IOException("Unable to create spill file " + filename);

			IFile.Writer<K, V> writer = new IFile.Writer<K, V>(conf, out,  inputKeyClass, inputValClass, codec);
			DataInputBuffer key = new DataInputBuffer();
			DataInputBuffer value = new DataInputBuffer();
			try {
				/* Copy over the data until done. */
				while (reader.next(key, value)) {
					writer.append(key, value);
				}
				writer.close();
				out.close();

				/* Write the index file. */
				indexOut.writeLong(0);
				indexOut.writeLong(writer.getRawLength());
				indexOut.writeLong(out.getPos());

				/* Close everything. */
				indexOut.flush();
				indexOut.close();

				JBufferSink.this.spill(taskid, progress, filename, indexFilename);
			} catch (Throwable e) {
				LOG.error("JBufferSink: error " + e + " during spill. progress = " + progress);
				e.printStackTrace();
			}
		}
		
		private void service(OutputFile.Header header, long length) throws IOException {
			DataInputBuffer key = new DataInputBuffer();
			DataInputBuffer value = new DataInputBuffer();
			
			IFile.Reader<K, V> reader = 
				new IFile.Reader<K, V>(conf, input, length, codec);
			
			if (header.type() == OutputFile.Type.SNAPSHOT) {
				try {
					LOG.debug("JBufferSink: " + ownerid + " collect snapshot " + 
							header.owner() + " progress = " + header.progress());
					snapshot.collect(header.owner().getTaskID(), reader, length, header.progress());
					updateProgress(header.owner().getTaskID(), header.progress());
				} catch (Throwable t) {
					t.printStackTrace();
					LOG.warn("Snapshot interrupted by " + t);
				}
			} else {
				if (task.isSnapshotting() || task.isMerging()) {
					/* Drain socket while task is snapshotting. */
					LOG.debug("JBufferSink: call spill for data " + header.owner() + " due to merging or snapshotting.");
					spill(header.owner().getTaskID(), header.progress(), reader, length);
				} else { 
					boolean doSpill = true;
						LOG.debug("JBufferSink: get task lock for " + header.owner() + " dump.");
						synchronized (task) {
							LOG.debug("JBufferSink: try to reserve " + length + " buffer space for buffer " + header.owner());
							/* Try to add records to the buffer. 
							 * Note: this means we can't back out the records so
							 * if we're in safemode this needs to be the final answer.
							 */
							if (buffer().reserve(length)) {
								LOG.debug("JBufferSink: dumping buffer " + header.owner() + ".");
								int records = 0;
								try {
									while (reader.next(key, value)) {
										records++;
										buffer().collect(key, value);
									}
								} catch (ChecksumException e) {
									e.printStackTrace();
								} catch (Throwable t) {
									t.printStackTrace();
								} finally {
									LOG.debug("JBufferSink: dumped " + records + " records.");
									buffer().unreserve(length);
									doSpill = false;
									updateProgress(header.owner().getTaskID(), header.progress());
								}
							}
							else {
								LOG.debug("JBufferSink: unable to reserve buffer space for " + header.owner());
							}
						}
					
					if (doSpill) {
						LOG.debug("JBufferSink: had to spill " + header.owner() + ".");
						spill(header.owner().getTaskID(), header.progress(), reader, length);
					}
				}
			}
		}
		
		public void run() {
			try {
				while (open) {
					long length = 0;
					OutputFile.Header header = null;
					try {
						length = this.input.readLong();
						header = OutputFile.Header.readHeader(this.input);
					}
					catch (Throwable e) {
						return;
					}
					
					if (length == 0) {
						updateProgress(header.owner().getTaskID(), header.progress());
					}
					else {
						long timestamp = System.currentTimeMillis();
						LOG.debug("JBufferSink receiving data from task " + header.owner() + 
								" service length " +  length + " progress = " + header.progress());
						service(header, length);
						LOG.debug("JBufferSink data from task " + header.owner() + " service time = " + 
								  (System.currentTimeMillis() - timestamp));
					}
				}
			} catch (Throwable e) {
				e.printStackTrace();
				return;
			}
			finally {
				close(); // must be called before done!
				done(this);
			}
		}
	}
}
