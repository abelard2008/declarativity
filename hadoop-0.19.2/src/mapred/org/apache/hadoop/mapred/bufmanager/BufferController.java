package org.apache.hadoop.mapred.bufmanager;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.DataInputBuffer;
import org.apache.hadoop.io.WritableUtils;
import org.apache.hadoop.io.compress.CodecPool;
import org.apache.hadoop.io.compress.CompressionCodec;
import org.apache.hadoop.io.compress.DefaultCodec;
import org.apache.hadoop.ipc.RPC;
import org.apache.hadoop.ipc.RPC.Server;
import org.apache.hadoop.mapred.IFile;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.JobID;
import org.apache.hadoop.mapred.FileHandle;
import org.apache.hadoop.mapred.TaskAttemptID;
import org.apache.hadoop.mapred.TaskID;
import org.apache.hadoop.mapred.TaskTracker;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.util.ReflectionUtils;

public class BufferController implements BufferUmbilicalProtocol {
	private static final Log LOG = LogFactory.getLog(BufferController.class.getName());
	
	private class RequestTransfer extends Thread {
		private Map<InetSocketAddress, Set<BufferRequest>> transfers;
		
		public RequestTransfer() {
			this.transfers = new HashMap<InetSocketAddress, Set<BufferRequest>>();
		}
		
		public void transfer(BufferRequest request) {
			synchronized(transfers) {
				InetSocketAddress source = 
					NetUtils.createSocketAddr(request.srcHost() + ":" + controlPort);
				if (!transfers.containsKey(source)) {
					transfers.put(source, new HashSet<BufferRequest>());
				}
				transfers.get(source).add(request);
				transfers.notify();
			}
		}
		
		public void run() {
			Set<InetSocketAddress> locations = new HashSet<InetSocketAddress>();
			Set<BufferRequest>     handle    = new HashSet<BufferRequest>();
			while (!isInterrupted()) {
				synchronized (transfers) {
					while (transfers.size() == 0) {
						try { transfers.wait();
						} catch (InterruptedException e) { }
					}
					locations.clear();
					locations.addAll(transfers.keySet());
				}
				
				for (InetSocketAddress location : locations) {
					synchronized(transfers) {
						handle.clear();
						handle.addAll(transfers.get(location));
					}
					Socket socket = null;
					DataOutputStream out = null;
					try {
						socket = new Socket();
						socket.connect(location);
						out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
						out.writeInt(handle.size());
						for (BufferRequest request : handle) {
							BufferRequest.write(out, request);
							LOG.debug("Sent request " + request + " to " + location);
						}
						out.flush();
						out.close();
						out = null;
						
						synchronized (transfers) {
							transfers.get(location).removeAll(handle);
							if (transfers.get(location).size() == 0) {
								transfers.remove(location);
							}
						}
					} catch (IOException e) {
						LOG.warn("BufferController: Trying to connect to " + location + "."
						          + " Request transfer connection issue " + e);
					} finally {
						try {
							if (out != null) {
								out.close();
							}
							
							if (socket != null) {
								socket.close();
							}
						} catch (Throwable t) {
							LOG.error(t);
						}
					}
				}

			}
		}

	};
	
	
	/**
	 * Manager for a partition being sent to a particular task.
	 * The partition data is sent from any task processing the 
	 * same input block. 
	 */
	private class RequestManager implements Comparable<RequestManager> {
		private BufferRequest.Type requestType;
		
		private JobConf conf;
		
		/* The destination task identifier. */
		private TaskAttemptID destination;
		
		/* The partition that we're interested in. */
		private int partition;
		
		/* The last sent output file taken from the keyed task. */
		private Map<TaskID, OutputFile> lastSentOutputFile;
		
		private Map<TaskID, Set<OutputFile>> sentOutputFiles;
		
		private InetSocketAddress address;
		
		private FSDataOutputStream out;
		
		private Set<TaskAttemptID> openTasks;
		
		public RequestManager(JobConf conf, BufferRequest request) throws IOException {
			this.conf = conf;
			this.requestType = request.type();
			this.destination = request.destination();
			this.partition = request.partition();
			this.address = request.destAddress();
			this.out = null;
			this.lastSentOutputFile = new ConcurrentHashMap<TaskID, OutputFile>();
			this.sentOutputFiles = new ConcurrentHashMap<TaskID, Set<OutputFile>>();
			this.openTasks = new HashSet<TaskAttemptID>();
		}
		
		@Override
		public String toString() {
			return "RequestManager destination " + destination;
		}
		
		@Override
		public int hashCode() {
			return this.destination.hashCode();
		}
		
		@Override
		public int compareTo(RequestManager o) {
			return this.partition - o.partition;
		}
		
		@Override
		public boolean equals(Object o) {
			if (o instanceof RequestManager) {
				return this.destination.equals(((RequestManager)o).destination);
			}
			return false;
		}
		
		public BufferRequest.Type requestType() {
			return this.requestType;
		}
		
		public TaskAttemptID destination() {
			return this.destination;
		}
		
		public int partition() {
			return this.partition;
		}
		
		public void close(TaskAttemptID taskid) throws IOException {
			synchronized (this) {
				this.openTasks.remove(taskid);
				if (out != null && this.openTasks.size() == 0) {
					LOG.debug(this + " closing connection.");
					out.close();
					out = null;
				}
			}
		}

		public boolean open(TaskAttemptID taskid) {
			synchronized (this) {
				if (out == null) {
					Socket socket = new Socket();
					try {
						try {
							socket.connect(this.address);
						} catch (IOException e) {
							LOG.warn("Connection error: " + this + ". "+ e);
							return false;
						}

						FSDataOutputStream stream = new FSDataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

						BufferRequestResponse response = new BufferRequestResponse();
						DataInputStream in = new DataInputStream(socket.getInputStream());
						response.readFields(in);
						if (!response.open) {
							stream.close();
							return false;
						}
						out = stream;
					} catch (Throwable e) {
						try { if (!socket.isClosed()) socket.close();
						} catch (Throwable t) { }
						return false;
					}
				}
				this.openTasks.add(taskid);
				return true;
			}
		}

		public boolean sentAny(OutputFile buffer) {
			return lastSentOutputFile.containsKey(buffer.header().owner().getTaskID());
		}

		public boolean sent(OutputFile buffer) {
			boolean sent = false;
			if (this.lastSentOutputFile.containsKey(buffer.header().owner().getTaskID())) {
				OutputFile lastSent = this.lastSentOutputFile.get(buffer.header().owner().getTaskID());
				sent = buffer.header().compareTo(lastSent.header()) <= 0;
			}
			return sent;
		}

		/**
		 * How many output files have been sent from the given task identifier.
		 * @param taskid The task identifier whose outputs are being shipped.
		 * @return The number of output files successfully sent.
		 */
		public int sentCount(TaskID taskid) {
			return this.sentOutputFiles.containsKey(taskid) ? 
					this.sentOutputFiles.get(taskid).size() : 0;
		}
		
		/**
		 * Flush the output file
		 * @param buffer
		 * @throws IOException
		 */
		public void send(OutputFile file) throws IOException {
			if (out == null) {
				throw new IOException(toString() + ". Attempt to flush output file when not open.");
			}

			OutputFile.Header header = file.header();
			try {
				synchronized (out) {
					if (sent(file)) {
						LOG.info(this + " already sent file " + header);
						return;
					}
					
					file.open(localFs); // TODO synchronized?
					long length = file.seek(partition);
					flush(out, file.dataInputStream(), length, header);
				}
			} catch (IOException e) {
				throw e;
			}

			this.lastSentOutputFile.put(header.owner().getTaskID(), file);
			if (!this.sentOutputFiles.containsKey(header.owner().getTaskID())) {
				this.sentOutputFiles.put(header.owner().getTaskID(), new HashSet<OutputFile>());
			}
			this.sentOutputFiles.get(header.owner().getTaskID()).add(file);
		}
		
		private void flush(FSDataOutputStream out, FSDataInputStream in, long length, OutputFile.Header header) throws IOException {
			if (length == 0 && header.progress() < 1.0f) {
				return;
			}

			CompressionCodec codec = null;
			if (conf.getCompressMapOutput()) {
				Class<? extends CompressionCodec> codecClass =
					conf.getMapOutputCompressorClass(DefaultCodec.class);
				codec = (CompressionCodec) ReflectionUtils.newInstance(codecClass, conf);
			}
			Class keyClass = (Class)conf.getMapOutputKeyClass();
			Class valClass = (Class)conf.getMapOutputValueClass();

			out.writeLong(length);
			OutputFile.Header.writeHeader(out, header);

			IFile.Reader reader = new IFile.Reader(conf, in, length, codec);
			IFile.Writer writer = new IFile.Writer(conf, out,  keyClass, valClass, codec);

			try {
				DataInputBuffer key = new DataInputBuffer();
				DataInputBuffer value = new DataInputBuffer();
				while (reader.next(key, value)) {
					writer.append(key, value);
				}
			} finally {
				out.flush();
				writer.close();
			}
		}

	}
	
	private class FileManager implements Runnable {
		
		private boolean open;
		private boolean busy;
		
		private TaskAttemptID taskid;
		
		/* Intermediate buffer outputs. */
		private List<OutputFile> outputs;
		
		/* The complete final output for the buffer. */
		private OutputFile finalOutput;
		
		/* Requests for partitions in my buffer. */
		private Set<RequestManager> requests;
		
		public FileManager(TaskAttemptID taskid) {
			this.taskid = taskid;
			this.outputs = new ArrayList<OutputFile>();
			this.finalOutput = null;
			this.requests = new TreeSet<RequestManager>();
			this.open = true;
			this.busy = false;
		}
		
		@Override
		public String toString() {
			return "FileManager: buffer " + taskid;
		}
		
		@Override
		public int hashCode() {
			return this.taskid.hashCode();
		}
		
		@Override
		public boolean equals(Object o) {
			if (o instanceof FileManager) {
				return ((FileManager)o).taskid.equals(taskid);
			}
			return false;
		}
		
		public float pipelineStats() {
			if (outputs.size() == 0) return 0f;
			float avgSentOutputs = 0f;
			for (RequestManager rm : requests) {
				avgSentOutputs += rm.sentCount(taskid.getTaskID());
			}
			avgSentOutputs /= (float) requests.size();    // average over all rm's.
			return avgSentOutputs > 0 ? 
					(float) outputs.size() / avgSentOutputs : (float) outputs.size();
		}
		
		public void close() {
			synchronized (this) {
				this.open = false;
				while (busy) {
					try { this.wait();
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}
				
				LOG.info(this + " closing.");
				/* flush remaining buffers. */
				while (somethingToSend()) {
					LOG.info(this + " flush remaining output files.");
					flush(this.outputs, this.requests);
					try { this.wait(100);
					} catch (InterruptedException e) { }
				}
			}
		}
		
		public void request(RequestManager request) throws IOException {
			synchronized (this) {
				if (!open) throw new IOException(this + " closed!");
				LOG.debug(this + " receive " + request);
				this.requests.add(request);
				this.notifyAll();
			}
			
		}
		
		public void add(OutputFile file) throws IOException {
			synchronized (this) {
				if (!open) throw new IOException(this + " closed!");
				if (file.complete() && file.header().type() != OutputFile.Type.SNAPSHOT) {
					LOG.debug(this + " received final output file.");
					this.finalOutput = file;
				}
				else {
					if (file.header().progress() == 1.0f) {
						LOG.debug(this + " received last output file");
					}
					this.outputs.add(file);
					LOG.debug(this + " received " + this.outputs.size() + " outputs.");
				}
				this.notifyAll();
			}
		}
		
		@Override
		public void run() {
			try {
				List<OutputFile>     outputs  = new ArrayList<OutputFile>();
				List<RequestManager> requests = new ArrayList<RequestManager>();
				while (open) {
					synchronized (this) {
						while (!somethingToSend()) {
							if (!open) return;
							LOG.debug(this + " nothing to send.");
							try { this.wait();
							} catch (InterruptedException e) { }
						}
						LOG.debug(this + " something to send.");
						outputs.addAll(this.outputs);
						requests.addAll(this.requests);
						busy = true;
					}

					Set<RequestManager> complete = null;
					try {
						complete = flush(outputs, requests);
					} finally {
						synchronized (this) {
							outputs.clear();
							requests.clear();
							if (complete != null) {
								this.requests.removeAll(complete);
							}
							busy = false;
							this.notifyAll();
						}
					}
				}
			} catch (Throwable t) {
				t.printStackTrace();
			} finally {
				LOG.info(this + " exiting.");
			}
		}

		private Set<RequestManager> flush(Collection<OutputFile> outputs, Collection<RequestManager> requests) {
			Set<RequestManager> satisfied = new HashSet<RequestManager>();
			if (this.finalOutput != null) {
				for (RequestManager request : requests) {
					if (!request.sentAny(this.finalOutput) && request.open(taskid)) {
						try {
							LOG.debug(this + " sending final complete output to task " + request.destination());
							request.send(this.finalOutput);
							request.close(taskid);
							satisfied.add(request);
						} catch (IOException e) {
							e.printStackTrace();
						}
					}
				}
			}
			
			for (OutputFile file : outputs) {
				for (RequestManager request : requests) {
					if (!request.sent(file)) {
						try {
							if (request.open(taskid)) {
								request.send(file);
								if (file.header().progress() == 1.0f) {
									LOG.debug(this + " successfully sent last output file to task " + request.destination());
									request.close(taskid);
									satisfied.add(request);
								}
							}
							else {
								LOG.info("Could not open " + request);
							}
						} catch (IOException e) {
							e.printStackTrace();
						}
					}
				}
			}
			return satisfied;
		}
		
		private boolean somethingToSend() {
			if (this.finalOutput != null) {
				for (RequestManager request : this.requests) {
					if (!request.sentAny(this.finalOutput)) {
						return true;
					}
				}
			}
			
			for (OutputFile file : this.outputs) {
				for (RequestManager request : this.requests) {
					if (!request.sent(file)) {
						return true;
					}
				}
			}
			return false;
		}
	}
	
    private TaskTracker tracker;
    
    private FileSystem localFs;
    
    private Thread acceptor;
    
    private RequestTransfer requestTransfer;
    
	private Executor executor;

	private Server server;

	private ServerSocketChannel channel;
	
	private int controlPort;
	
	private String hostname;
	
	/* Managers for job level requests (i.e., reduce requesting map outputs). */
	private Map<JobID, Set<RequestManager>> mapRequestManagers;
	
	/* Managers for task level requests (i.e., a map requesting the output of a reduce). */
	private Map<TaskID, Set<RequestManager>> reduceRequestManagers;
	
	/* Each task will have a file manager associated with it. */
	private Map<JobID, Map<TaskAttemptID, FileManager>> fileManagers;

	public BufferController(TaskTracker tracker) throws IOException {
		this.tracker   = tracker;
		this.localFs   = FileSystem.getLocal(tracker.conf());
		this.requestTransfer = new RequestTransfer();
		this.executor  = Executors.newCachedThreadPool();
		this.mapRequestManagers  = new HashMap<JobID, Set<RequestManager>>();
		this.reduceRequestManagers = new HashMap<TaskID, Set<RequestManager>>();
		this.fileManagers = new HashMap<JobID, Map<TaskAttemptID, FileManager>>();
		this.hostname = InetAddress.getLocalHost().getCanonicalHostName();
	}

	public static InetSocketAddress getControlAddress(Configuration conf) {
		try {
			int port = conf.getInt("mapred.buffer.manager.data.port", 9021);
			String address = InetAddress.getLocalHost().getCanonicalHostName();
			address += ":" + port;
			return NetUtils.createSocketAddr(address);
		} catch (Throwable t) {
			return NetUtils.createSocketAddr("localhost:9021");
		}
	}

	public static InetSocketAddress getServerAddress(Configuration conf) {
		try {
			String address = InetAddress.getLocalHost().getCanonicalHostName();
			int port = conf.getInt("mapred.buffer.manager.control.port", 9020);
			address += ":" + port;
			return NetUtils.createSocketAddr(address);
		} catch (Throwable t) {
			return NetUtils.createSocketAddr("localhost:9020");
		}
	}

	public void open() throws IOException {
		Configuration conf = tracker.conf();
		int maxMaps = conf.getInt("mapred.tasktracker.map.tasks.maximum", 2);
		int maxReduces = conf.getInt("mapred.tasktracker.reduce.tasks.maximum", 1);

		InetSocketAddress serverAddress = getServerAddress(conf);
		this.server = RPC.getServer(this, serverAddress.getHostName(), serverAddress.getPort(), 
				maxMaps + maxReduces, false, conf);
		this.server.start();

		this.requestTransfer.setPriority(Thread.MAX_PRIORITY);
		this.requestTransfer.start();
		
		/** The server socket and selector registration */
		InetSocketAddress controlAddress = getControlAddress(conf);
		this.controlPort = controlAddress.getPort();
		this.channel = ServerSocketChannel.open();
		this.channel.socket().bind(controlAddress);
		
		this.acceptor = new Thread() {
			@Override
			public void run() {
				while (!isInterrupted()) {
					SocketChannel connection = null;
					try {
						connection = channel.accept();
						DataInputStream in = new DataInputStream(new BufferedInputStream(connection.socket().getInputStream()));
						int numRequests = in.readInt();
						for (int i = 0; i < numRequests; i++) {
							BufferRequest request = BufferRequest.read(in);
							if (request.type() == BufferRequest.Type.MAP) {
								request((MapBufferRequest) request);
							}
							else {
								LOG.debug("BufferController accept " + request);
								request((ReduceBufferRequest) request);
							}
						}
					} catch (IOException e) {
						e.printStackTrace();
					}
					finally {
						try {
							if (connection != null) connection.close();
						} catch (IOException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
					}
				}
			}
		};
		this.acceptor.setDaemon(true);
		this.acceptor.setPriority(Thread.MAX_PRIORITY);
		this.acceptor.start();
	}
	
	public void close() {
		this.acceptor.interrupt();
		this.server.stop();
		this.requestTransfer.interrupt();
		try { this.channel.close();
		} catch (Throwable t) {}
	}
	
	@Override
	public long getProtocolVersion(String protocol, long clientVersion)
	throws IOException {
		return 0;
	}
	
	public synchronized void free(JobID jobid) {
		LOG.info("BufferController freeing job " + jobid);
		
		/* Close all file managers. */
		if (this.fileManagers.containsKey(jobid)) {
			Map<TaskAttemptID, FileManager> fm_map = this.fileManagers.get(jobid);
			for (FileManager fm : fm_map.values()) {
				fm.close();
			}
			this.fileManagers.remove(jobid);
		}
		
		/* blow away map request managers. */
		if (this.mapRequestManagers.containsKey(jobid)) {
			this.mapRequestManagers.remove(jobid);
		}
		
		/* blow away reduce request managers. */
		Set<TaskID> rids = new HashSet<TaskID>();
		for (TaskID rid : this.reduceRequestManagers.keySet()) {
			if (rid.getJobID().equals(jobid)) {
				rids.add(rid);
			}
		}
		for (TaskID rid : rids) {
			this.reduceRequestManagers.remove(rid);
		}
	}
	
	@Override
	public synchronized float pipestat(TaskAttemptID owner) throws IOException {
		if (!fileManagers.containsKey(owner.getJobID())) {
			return 0;
		}
		Map<TaskAttemptID, FileManager> fm_map = fileManagers.get(owner.getJobID());
		if (!fm_map.containsKey(owner)) {
			return 0;
		}
		return fm_map.get(owner).pipelineStats();
	}
	
	@Override
	public synchronized void output(OutputFile output) throws IOException {
		OutputFile.Header header = output.header();
		JobID jobid = header.owner().getJobID();
		if (!fileManagers.containsKey(jobid)) {
			fileManagers.put(jobid, new HashMap<TaskAttemptID, FileManager>());
		}
		Map<TaskAttemptID, FileManager> taskFileManager = fileManagers.get(jobid);
		if (!taskFileManager.containsKey(header.owner())) {
			LOG.info("BufferController: create new FileManager for task " + header.owner());
			FileManager fm = new FileManager(header.owner());
			taskFileManager.put(header.owner(), fm);
			register(fm);
		}
		taskFileManager.get(header.owner()).add(output);
	}
	
	@Override
	public synchronized void request(ReduceBufferRequest request) throws IOException {
		if (request.srcHost().equals(hostname)) {
			LOG.debug("Register " + request);
			register(request);
		}
		else {
			LOG.debug("Transfer " + request);
			requestTransfer.transfer(request); // request is remote.
		}
	}
	
	@Override
	public synchronized void request(MapBufferRequest request) throws IOException {
		if (request.srcHost().equals(hostname)) {
			LOG.debug("Register " + request);
			register(request);
		}
		else {
			LOG.debug("Transfer " + request);
			requestTransfer.transfer(request); // request is remote.
		}
	}

	/******************** PRIVATE METHODS ***********************/
	
	private void register(MapBufferRequest request) throws IOException {
		JobID jobid = request.mapJobId();
		JobConf job = tracker.getJobConf(jobid);
		RequestManager manager = new RequestManager(job, request);
		if (!this.mapRequestManagers.containsKey(jobid)) {
			this.mapRequestManagers.put(jobid, new HashSet<RequestManager>());
			this.mapRequestManagers.get(jobid).add(manager);
		}
		else if (!this.mapRequestManagers.get(jobid).contains(manager)) {
			this.mapRequestManagers.get(jobid).add(manager);
			if (this.mapRequestManagers.get(jobid).size() > job.getNumReduceTasks()) {
				LOG.warn("more request managers than reduces!");
			}
		}
		else {
			LOG.debug("BufferController: request manager already exists." + request);
			manager = null;
		}
		
		if (manager != null) {
			if (this.fileManagers.containsKey(jobid)) {
				for (FileManager fm : this.fileManagers.get(jobid).values()) {
					fm.request(manager);
				}
			}
		}
	}
	
	private void register(ReduceBufferRequest request) throws IOException {
		LOG.debug("BufferController register reduce request " + request);

		TaskID taskid = request.reduceTaskId();
		JobConf job = tracker.getJobConf(taskid.getJobID());
		RequestManager manager = new RequestManager(job, request);
		if (!this.reduceRequestManagers.containsKey(taskid)) {
			this.reduceRequestManagers.put(taskid, new HashSet<RequestManager>());
			this.reduceRequestManagers.get(taskid).add(manager);
		}
		else if (!this.reduceRequestManagers.get(taskid).contains(manager)) {
			this.reduceRequestManagers.get(taskid).add(manager);
		}
		else {
			LOG.debug("BufferController " + manager + 
					  " already exists. request " + request);
			manager = null;
		}
		
		if (manager != null) {
			if (this.fileManagers.containsKey(taskid.getJobID())) {
				for (TaskAttemptID attempt : this.fileManagers.get(taskid.getJobID()).keySet()) {
					if (attempt.getTaskID().equals(taskid)) {
						FileManager fm = this.fileManagers.get(taskid.getJobID()).get(attempt);
						fm.request(manager);
					}
				}
			}
		}
	}
	
	private void register(FileManager fm) throws IOException {
		JobID jobid = fm.taskid.getJobID();
		if (fm.taskid.isMap()) {
			if (this.mapRequestManagers.containsKey(jobid)) {
				for (RequestManager rm : this.mapRequestManagers.get(jobid)) {
					fm.request(rm);
				}
			}
		}
		else {
			TaskID taskid = fm.taskid.getTaskID();
			if (this.reduceRequestManagers.containsKey(taskid)) {
				for (RequestManager rm : this.reduceRequestManagers.get(taskid)) {
					fm.request(rm);
				}
			}
		}
		executor.execute(fm);
	}

}
