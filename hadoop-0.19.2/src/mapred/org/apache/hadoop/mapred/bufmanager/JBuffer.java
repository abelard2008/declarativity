package org.apache.hadoop.mapred.bufmanager;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.BufferedFSInputStream;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.DataInputBuffer;
import org.apache.hadoop.io.RawComparator;
import org.apache.hadoop.io.compress.CompressionCodec;
import org.apache.hadoop.io.compress.DefaultCodec;
import org.apache.hadoop.io.serializer.Deserializer;
import org.apache.hadoop.io.serializer.SerializationFactory;
import org.apache.hadoop.io.serializer.Serializer;
import org.apache.hadoop.mapred.IFile;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.FileHandle;
import org.apache.hadoop.mapred.Merger;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.Partitioner;
import org.apache.hadoop.mapred.RawKeyValueIterator;
import org.apache.hadoop.mapred.Reducer;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.TaskAttemptID;
import org.apache.hadoop.mapred.Merger.Segment;
import org.apache.hadoop.util.IndexedSortable;
import org.apache.hadoop.util.IndexedSorter;
import org.apache.hadoop.util.Progress;
import org.apache.hadoop.util.QuickSort;
import org.apache.hadoop.util.ReflectionUtils;

public class JBuffer<K extends Object, V extends Object>  implements JBufferCollector<K, V>, IndexedSortable {

	protected static class CombineOutputCollector<K extends Object, V extends Object> 
	implements OutputCollector<K, V> {
		private IFile.Writer<K, V> writer = null;

		public CombineOutputCollector() {
		}
		public synchronized void setWriter(IFile.Writer<K, V> writer) {
			this.writer = writer;
		}

		public synchronized void reset() {
			this.writer = null;
		}

		public synchronized void collect(K key, V value)
		throws IOException {
			if (writer != null) writer.append(key, value);
		}
	}

	private class JBufferMerger {

		private int snapshots;

		public JBufferMerger() throws IOException {
			this.snapshots = 0;
		}

		/**
		 * Create the final output file from all spill files.
		 * Spill files are not deleted.
		 * @throws IOException
		 */
		public synchronized OutputFile mergeFinal() throws IOException {
			List<JBufferFile> finalSpills = new ArrayList<JBufferFile>();
			long finalDataSize = 0;
			long indexFileSize = partitions * MAP_OUTPUT_INDEX_RECORD_LENGTH;

			for (JBufferFile spill : spills) {
				if (spill.valid()) {
					finalSpills.add(spill);
					finalDataSize += spill.dataSize();
				}
			}
			LOG.info("JBufferMerger: final merge size " + finalDataSize + ". Spill files: " + finalSpills.toString());
			
			Path dataFile = outputHandle.getOutputFileForWrite(taskid,  finalDataSize);
			Path indexFile = outputHandle.getOutputIndexFileForWrite(taskid, indexFileSize);
			JBufferFile finalOutput = new JBufferFile(dataFile, indexFile);
			merge(finalSpills, finalOutput);
			return  new OutputFile(taskid, 1f, inputFileName, inputStart, inputEnd, 
					               spills.size(), finalOutput.data, finalOutput.index, true);
		}

		/**
		 * Used by the reducer to keep input data files
		 * from growing too large.
		 * @throws IOException
		 */
		public synchronized OutputFile mergeSpill(int start, int end) throws IOException {
			List<JBufferFile> mergeSpills = new ArrayList<JBufferFile>();
			long dataFileSize = 0;
			long indexFileSize = partitions * MAP_OUTPUT_INDEX_RECORD_LENGTH;

			for (int i = start; i <= end; i++) {
				if (spills.get(i).valid()) {
					mergeSpills.add(spills.get(i));
					dataFileSize += spills.get(i).dataSize();
				}
			}
			LOG.info("JBufferMerger: intermediate merge size " + dataFileSize + ". Spill files: " + mergeSpills.toString());

			
			int snapshotId = snapshots++;
			Path dataFile = outputHandle.getOutputSnapshotFileForWrite(taskid, snapshotId, dataFileSize);
			Path indexFile = outputHandle.getOutputSnapshotIndexFileForWrite(taskid, snapshotId, indexFileSize);
			JBufferFile snapshot = new JBufferFile(dataFile, indexFile);
			
			merge(mergeSpills, snapshot);
			
			JBufferFile last = null;
			for (JBufferFile spill : mergeSpills) {
				spill.delete();
				last = spill;
			}
			LOG.info("JBufferMerger: intermediate merge snapshot " + snapshot + ".");
			last.rename(snapshot);
			LOG.info("JBufferMerger: intermediate merge rename " + last + ".");
			
			return new OutputFile(taskid, getProgress().get(), inputFileName, inputStart, inputEnd, 
					               end, last.data, last.index, false);
		}

		/**
		 * Generate snapshot output file.
		 * @return The snapshot output file.
		 * @throws IOException
		 */
		public synchronized OutputFile mergeSnapshot() throws IOException {
			List<JBufferFile> mergeSpills = new ArrayList<JBufferFile>();
			long dataFileSize = 0;
			long indexFileSize = partitions * MAP_OUTPUT_INDEX_RECORD_LENGTH;

			for (JBufferFile spill : spills) {
				if (spill.valid()) {
					mergeSpills.add(spill);
					dataFileSize += spill.dataSize();
				}
			}
			
			int snapshotId = snapshots++;
			Path dataFile = outputHandle.getOutputSnapshotFileForWrite(taskid, snapshotId, dataFileSize);
			Path indexFile = outputHandle.getOutputSnapshotIndexFileForWrite(taskid, snapshotId, indexFileSize);
			JBufferFile snapshot = new JBufferFile(dataFile, indexFile);
			
			merge(mergeSpills, snapshot);
			return new OutputFile(taskid, getProgress().get(), snapshot.data, snapshot.index);
		}
		
		private void merge(List<JBufferFile> spills, JBufferFile output) throws IOException {
			if (spills.size() == 1) {
				output.copy(spills.get(0));
			}

			FSDataOutputStream dataOut = localFs.create(output.data, true);
			FSDataOutputStream indexOut = localFs.create(output.index, true);

			if (spills.size() == 0) {
				//create dummy files
				writeEmptyOutput(dataOut, indexOut);
				dataOut.close();
				indexOut.close();
			} else {
				for (int parts = 0; parts < partitions; parts++){
					//create the segments to be merged
					BufferRequest request = requestMap.containsKey(parts) ? requestMap.get(parts) : null;

					List<Segment<K, V>> segmentList =
						new ArrayList<Segment<K, V>>(spills.size());
					for(JBufferFile spill : spills) {
						FSDataInputStream indexIn = localFs.open(spill.index);
						indexIn.seek(parts * MAP_OUTPUT_INDEX_RECORD_LENGTH);
						long segmentOffset = indexIn.readLong();
						long rawSegmentLength = indexIn.readLong();
						long segmentLength = indexIn.readLong();
						indexIn.close();
						FSDataInputStream in = localFs.open(spill.data);
						in.seek(segmentOffset);
						Segment<K, V> s = 
							new Segment<K, V>(new IFile.Reader<K, V>(job, in, segmentLength, codec), true);
						segmentList.add(s);
					}

					//merge
					@SuppressWarnings("unchecked")
					RawKeyValueIterator kvIter = 
						Merger.merge(job, localFs, 
								keyClass, valClass,
								segmentList, job.getInt("io.sort.factor", 100), 
								new Path(taskid.toString()), 
								job.getOutputKeyComparator(), reporter);

					//write merged output to disk
					long segmentStart = dataOut.getPos();
					IFile.Writer<K, V> writer = 
						new IFile.Writer<K, V>(job, dataOut, keyClass, valClass, codec);
					if (null == combinerClass || spills.size() < minSpillsForCombine) {
						Merger.writeFile(kvIter, writer, reporter, job);
					} else {
						CombineOutputCollector combineCollector = new CombineOutputCollector();
						combineCollector.setWriter(writer);
						combineAndSpill(combineCollector, kvIter);
					}

					//close
					writer.close();

					//write index record
					writeIndexRecord(indexOut, dataOut, segmentStart, writer);
				}
				dataOut.close();
				indexOut.close();
			}
		}

	}

	private class SpillThread extends Thread {
		private boolean spill = false;
		private boolean open = true;
		private int nextPipelineSpill = 0;
		private boolean pipelineFinal = false;

		public void doSpill() {
			synchronized (spillLock) {
				if (open) {
					kvend = kvindex;
					bufend = bufmark;
					this.spill = kvstart != kvend;
					if (this.spill) spillLock.notifyAll();
				}
			}
		}
		
		public void forceSpill() throws IOException {
			synchronized (spillLock) {
				kvend = kvindex;
				bufend = bufmark;
				if (kvstart != kvend) { 
					spill(); // force a spill
				}
			}
		}

		public boolean isSpilling() {
			return this.spill;
		}

		public void close() throws IOException {
			if (this.open == false) return;
			synchronized (spillLock) {
				this.open = false;
				while (isSpilling()) {
					try { spillLock.wait();
					} catch (InterruptedException e) { }
				}
				
				forceSpill(); // flush what remains
				if (pipeline) {
					pipeline();
				}
				spillLock.notifyAll();
			}
		}

		private void spill() throws IOException {
			if (kvstart != kvend) { 
				LOG.info("SpillThread: begin sort and spill.");
				long sortstart = java.lang.System.currentTimeMillis();
				float reduction = sortAndSpill(); 
				LOG.info("SpillThread: sort/spill time " + 
							((System.currentTimeMillis() - sortstart)/1000f) + " secs. " +
							"Data reduction = " + reduction);
				if (pipeline && !pipelineFinal) {
					float pipestat = umbilical.pipestat(taskid);
					if (pipestat <= 2.0f && 
							(reduction * (spills.size() - nextPipelineSpill)) >= 1.0f) {
						LOG.info("JBuffer: " + taskid + " call pipeline.");
						pipeline();
						LOG.info("JBuffer: " + taskid + " call pipeline done.");
					}
					else {
						LOG.info("JBuffer " + taskid + 
								" hold off on pipelining. " +
								"Pipeline statistic = " +  pipestat);
					}
				}
			}
		}
		
		/**
		 * Pipeline whatever data has been spilled. Three cases:
		 * 1. Nothing spilled. An empty spill file is created and pipelined 
		 * with a progress of 1f.
		 * 2. A single spill file. The spill file is sent with the current buffer
		 * progress attached.
		 * 3. More than one spill files. The spills are merged into a single spill
		 * file and sent with the current buffer progress.
		 * @throws IOException
		 */
		private void pipeline() throws IOException {
			if (spills.size() == nextPipelineSpill) {
				/* Create a dummy sentinel spill file. */
				LOG.info("JBuffer: create sentinel spill file for pipelining.");
				int dataSize = partitions * APPROX_HEADER_LENGTH;
				Path data = outputHandle.getSpillFileForWrite(taskid, spills.size(), dataSize);
				FSDataOutputStream dataOut = localFs.create(data, false);
				Path index = outputHandle.getSpillIndexFileForWrite(
						taskid, spills.size(), partitions * MAP_OUTPUT_INDEX_RECORD_LENGTH);
				FSDataOutputStream indexOut = localFs.create(index, false);
				writeEmptyOutput(dataOut, indexOut);
				dataOut.close(); indexOut.close();
				umbilical.output(new OutputFile(taskid, 1f, 
						                        inputFileName, inputStart, inputEnd, 
						                        spills.size() + 1, data, index, false));
			}
			else if (spills.size() - nextPipelineSpill == 1) {
				LOG.info("JBuffer " + taskid + " pipeline spill " + (spills.size() - 1) + ".");
				JBufferFile spill = spills.get(spills.size() - 1);
				umbilical.output(new OutputFile(taskid, getProgress().get(), 
						inputFileName, inputStart, inputEnd, 
						spills.size(), spill.data, spill.index, false));
			}
			else {
				LOG.info("Merging spills " + nextPipelineSpill + " - " + 
						(spills.size() - 1) + " before pipelining. Progress = " + getProgress().get());
				OutputFile mergedSpill = merger.mergeSpill(nextPipelineSpill, spills.size() - 1);
				umbilical.output(mergedSpill);
			}
			nextPipelineSpill = spills.size();
			pipelineFinal = getProgress().get() == 1f;
		}

		@Override
		public void run() {
			try {
				while (open) {
					synchronized (spillLock) {
						while (open && ! spill) {
							try {
								spillLock.wait();
							} catch (InterruptedException e) {
								return;
							}
						}
					}

					if (open) {
						try {
							spill();
						} catch (Throwable e) {
							e.printStackTrace();
							sortSpillException = e;
						} finally {
							synchronized(spillLock) {
								if (bufend < bufindex && bufindex < bufstart) {
									bufvoid = kvbuffer.length;
								}
								kvstart = kvend;
								bufstart = bufend;
								spill = false;
								spillLock.notifyAll();
							}

							synchronized (mergeLock) {
								mergeLock.notifyAll();
							}
						}
					}
				}
			} finally {
				synchronized (spillLock) {
					open  = false;
					spill = false;
					spillLock.notifyAll();
				}
			}
		}
	}

	private class MergeThread extends Thread {
		private boolean open = true;
		private boolean busy = false;
		private int spillMergeBoundary = 0;

		public void close() {
			if (open) {
				synchronized (mergeLock) {
					open = false;
					mergeLock.notifyAll();
					while (busy) {
						try { mergeLock.wait();
						} catch (InterruptedException e) { }
					}
				}
				LOG.debug("MergeThread is closed.");
			}
		}

		public boolean isBusy() {
			return busy;
		}


		@Override
		public void run() {
			try {
				int threshold = 2 * job.getInt("io.sort.factor", 100);
				LOG.info("JBuffer: merge thread running.");
				while (open) {
					synchronized (mergeLock) {
						busy = false;
						while (open && spills.size() - spillMergeBoundary <= threshold) {
							try {
								mergeLock.wait();
							} catch (InterruptedException e) {
								return;
							}
						}
						if (!open) return;
						busy = true;
					}

					if (!taskid.isMap() && spills.size() - spillMergeBoundary > threshold) {
						try {
							long mergestart = java.lang.System.currentTimeMillis();
							int end = spills.size() - 1;
							merger.mergeSpill(spillMergeBoundary, end);
							spillMergeBoundary = end;
							LOG.debug("MergeThread: merge time " +  ((System.currentTimeMillis() - mergestart)/1000f) + " secs.");
						} catch (IOException e) {
							e.printStackTrace();
							sortSpillException = e;
							return; // dammit
						}
					}
				}
			} finally {
				synchronized (mergeLock) {
					LOG.info("MergeThread exit.");
					open  = false;
					busy  = false;
					mergeLock.notifyAll();
				}
			}
		}
	}
	
	private class JBufferFile {
		Path data;
		
		Path index;
		
		boolean valid;
		
		public JBufferFile(Path data, Path index) {
			this.data = data;
			this.index = index;
			this.valid = true;
		}
		
		@Override
		public String toString() {
			return data.getName() + "[" + dataSize() + " bytes]";
		}
		
		public void rename(JBufferFile file) throws IOException {
			if (!file.valid()) { 
				throw new IOException("JBufferFile: rename from an unvalid file!");
			} else if (this.valid()) {
				throw new IOException("JBufferFile: rename to a valid file not allowed!");
			}
			this.valid = true;
			file.valid = false;
			localFs.rename(file.data, this.data);
			localFs.rename(file.index, this.index);
		}
		
		public void copy(JBufferFile file) throws IOException {
			if (!file.valid()) { 
				throw new IOException("JBufferFile: copy from an unvalid file!");
			}
			this.valid = true;
			localFs.copyFromLocalFile(file.data, this.data);
			localFs.copyFromLocalFile(file.index, this.index);
		}
		
		public long dataSize() {
			try {
				return valid ? localFs.getFileStatus(data).getLen() : 0;
			} catch (IOException e) {
				e.printStackTrace();
				return 0;
			}
		}
		
		public boolean valid() {
			return this.valid;
		}
		
		public void delete() throws IOException {
			this.valid = false;
			if (localFs.exists(data)) {
				localFs.delete(data, true);
			}
			if (localFs.exists(index)) {
				localFs.delete(index, true);
			}
		}
	}
	
	private static final Log LOG = LogFactory.getLog(JBuffer.class.getName());

	private Progress progress;

	/**
	 * The size of each record in the index file for the map-outputs.
	 */
	public static final int MAP_OUTPUT_INDEX_RECORD_LENGTH = 24;

	public final static int APPROX_HEADER_LENGTH = 150;

	private final BufferUmbilicalProtocol umbilical;

	private final int partitions;
	private final Partitioner<K, V> partitioner;
	private final JobConf job;
	private final TaskAttemptID taskid;
	private final Reporter reporter;
	private final Class<K> keyClass;
	private final Class<V> valClass;
	private final RawComparator<K> comparator;
	private final SerializationFactory serializationFactory;
	private final Serializer<K> keySerializer;
	private final Serializer<V> valSerializer;
	private final Class<? extends Reducer> combinerClass;

	// Compression for map-outputs
	private CompressionCodec codec = null;

	// k/v accounting
	private volatile int kvstart = 0;  // marks beginning of spill
	private volatile int kvend = 0;    // marks beginning of collectable
	private int kvindex = 0;           // marks end of collected
	private final int[] kvoffsets;     // indices into kvindices
	private final int[] kvindices;     // partition, k/v offsets into kvbuffer
	private volatile int bufstart = 0; // marks beginning of spill
	private volatile int bufend = 0;   // marks beginning of collectable
	private volatile int bufvoid = 0;  // marks the point where we should stop
	// reading at the end of the buffer
	private int bufindex = 0;          // marks end of collected
	private int bufmark = 0;           // marks end of record
	private byte[] kvbuffer;           // main output buffer
	private long kvbufferSize = 0;
	private static final int PARTITION = 0; // partition offset in acct
	private static final int KEYSTART = 1;  // key offset in acct
	private static final int VALSTART = 2;  // val offset in acct
	private static final int ACCTSIZE = 3;  // total #fields in acct
	private static final int RECSIZE =
		(ACCTSIZE + 1) * 4;  // acct bytes per record

	// spill accounting
	private List<JBufferFile> spills = new ArrayList<JBufferFile>();
	private volatile Throwable sortSpillException = null;
	private final int softRecordLimit;
	private final int softBufferLimit;
	private final int minSpillsForCombine;
	private final IndexedSorter sorter;
	private final Object spillLock = new Object();
	private final Object mergeLock = new Object();
	private final BlockingBuffer bb = new BlockingBuffer();

	private long reserve = 0;

	private final FileSystem localFs;

	private FileHandle outputHandle = null;

	private SpillThread spillThread;
	private MergeThread mergeThread;
	private JBufferMerger merger;

	private boolean pipeline = false;
	
	private String inputFileName = null;
	
	private long inputStart = 0;
	
	private long inputEnd = 0;

	private TreeSet<BufferRequest> requests = new TreeSet<BufferRequest>();
	private Map<Integer, BufferRequest> requestMap = new HashMap<Integer, BufferRequest>();

	@SuppressWarnings("unchecked")
	public JBuffer(BufferUmbilicalProtocol umbilical, TaskAttemptID taskid, JobConf job, Reporter reporter, 
			       Class<K> keyClass, Class<V> valClass, Class<? extends CompressionCodec> codecClass) throws IOException {
		this.umbilical = umbilical;
		this.taskid = taskid;
		this.job = job;
		this.reporter = reporter;
		this.outputHandle = new FileHandle(taskid.getJobID());
		this.outputHandle.setConf(job);

		this.progress = new Progress();
		this.progress.set(0f);

		this.spillThread = new SpillThread();
		this.spillThread.setDaemon(true);
		this.spillThread.start();

		this.mergeThread = new MergeThread();
		this.mergeThread.setDaemon(true);
		this.mergeThread.setPriority(Thread.MAX_PRIORITY);
		if (!taskid.isMap()) {
			this.mergeThread.start();
		}
		this.merger = new JBufferMerger();

		localFs = FileSystem.getLocal(job);
		partitions = taskid.isMap() ? job.getNumReduceTasks() : 1;
		partitioner = (Partitioner)
		ReflectionUtils.newInstance(job.getPartitionerClass(), job);
		// sanity checks
		final float spillper = job.getFloat("io.sort.spill.percent",(float)0.8);
		final float recper = job.getFloat("io.sort.record.percent",(float)0.05);
		final int sortmb = job.getInt("io.sort.mb", 100);
		if (spillper > (float)1.0 || spillper < (float)0.0) {
			throw new IOException("Invalid \"io.sort.spill.percent\": " + spillper);
		}
		if (recper > (float)1.0 || recper < (float)0.01) {
			throw new IOException("Invalid \"io.sort.record.percent\": " + recper);
		}
		if ((sortmb & 0x7FF) != sortmb) {
			throw new IOException("Invalid \"io.sort.mb\": " + sortmb);
		}
		sorter = (IndexedSorter)
		ReflectionUtils.newInstance(
				job.getClass("map.sort.class", QuickSort.class), job);
		// buffers and accounting
		int maxMemUsage = sortmb << 20;

		float maxInMemCopyUse = 0.7f;
		if (taskid.isMap()) {
			maxInMemCopyUse = job.getFloat("mapred.job.map.buffer.percent", 0.70f);
		}
		else {
			maxInMemCopyUse = job.getFloat("mapred.job.shuffle.input.buffer.percent", 0.70f);
		}
		maxMemUsage = (int)Math.min(Runtime.getRuntime().maxMemory() * maxInMemCopyUse, maxMemUsage);

		int recordCapacity = (int)(maxMemUsage * recper);
		recordCapacity -= recordCapacity % RECSIZE;
		kvbufferSize = maxMemUsage - recordCapacity;
		kvbuffer = new byte[(int)kvbufferSize];
		bufvoid = kvbuffer.length;
		recordCapacity /= RECSIZE;
		kvoffsets = new int[recordCapacity];
		kvindices = new int[recordCapacity * ACCTSIZE];
		softBufferLimit = (int)(kvbuffer.length * spillper);
		softRecordLimit = (int)(kvoffsets.length * spillper);
		// k/v serialization
		this.comparator = job.getOutputKeyComparator();
		this.keyClass = keyClass;
		this.valClass = valClass;
		this.serializationFactory = new SerializationFactory(job);
		this.keySerializer = serializationFactory.getSerializer(keyClass);
		this.keySerializer.open(bb);
		this.valSerializer = serializationFactory.getSerializer(valClass);
		this.valSerializer.open(bb);

		// compression
		if (codecClass != null) {
			codec = (CompressionCodec) ReflectionUtils.newInstance(codecClass, job);
		}
		// combiner
		combinerClass = job.getCombinerClass();
		minSpillsForCombine = job.getInt("min.num.spills.for.combine", 3);
	}

	/**
	 * Initialize the input file information.
	 * @param inputFileName Input file name.
	 * @param inputStart Input start offset.
	 * @param inputEnd Input end offset.
	 * @param pipeline true if we pipeline, otherwise only final output is sent.
	 */
	public void input(String inputFileName, long inputStart, long inputEnd, boolean pipeline) {
		this.inputFileName = inputFileName;
		this.inputStart = inputStart;
		this.inputEnd = inputEnd;
		this.pipeline = pipeline;
	}
	
	/**
	 * Input file is taken from the given output. This
	 * is the final output from a reduce shuffle phase.
	 * @param pipeline
	 * @throws IOException 
	 */
	public void input(OutputFile file, boolean pipeline) throws IOException {
		long length = localFs.getFileStatus(file.data()).getLen();
		this.inputFileName = file.data().getName();
		this.inputStart = 0;
		this.inputEnd = length;
		this.pipeline = pipeline;
	}
	
	public Progress getProgress() {
		return this.progress;
	}

	public void setProgress(Progress progress) {
		this.progress = progress;
	}

	public JobConf getJobConf() {
		return this.job;
	}

	public int getNumberOfPartitions() {
		return this.partitions;
	}

	/**
	 * @return The number of free bytes.
	 */
	public int getBytes() {
		return ((kvend > kvstart) ? kvend : kvoffsets.length + kvend) - kvstart;
	}

	public synchronized boolean reserve(long bytes) {
		if (kvbuffer == null) return false;
		else if (kvbufferSize - this.reserve > bytes) {
			this.reserve += bytes;
			return true;
		}
		return false;
	}

	public void unreserve(long bytes) {
		this.reserve -= bytes;

		if (this.reserve < 0) {
			LOG.error("Reserve bytes error: " + this.reserve);
			this.reserve = 0;
		}
	}

	/**
	 * Optimized collection routine used by the reducer.
	 * @param key
	 * @param value
	 * @throws IOException
	 */
	public synchronized void collect(DataInputBuffer key, DataInputBuffer value) throws IOException {
		if (sortSpillException != null) {
			throw (IOException)new IOException("Spill failed"
			).initCause(sortSpillException);
		}
		else if (this.partitions > 1) {
			throw new IOException("Method not for use with more than one partition");
		}

		try {
			int keystart = bufindex;
			bb.write(key.getData(), key.getPosition(), key.getLength() - key.getPosition());
			if (bufindex < keystart) {
				// wrapped the key; reset required
				bb.reset();
				keystart = 0;
			}

			// copy value bytes into buffer
			int valstart = bufindex;
			bb.write(value.getData(), value.getPosition(), value.getLength() - value.getPosition());

			if (keystart == bufindex) {
				// if emitted records make no writes, it's possible to wrap
				// accounting space without notice
				bb.write(new byte[0], 0, 0);
			}
			int valend = bb.markRecord();


			// update accounting info
			int ind = kvindex * ACCTSIZE;
			kvoffsets[kvindex] = ind;
			kvindices[ind + PARTITION] = 0;
			kvindices[ind + KEYSTART] = keystart;
			kvindices[ind + VALSTART] = valstart;
			kvindex = (kvindex + 1) % kvoffsets.length;
		} catch (MapBufferTooSmallException e) {
			LOG.info("Record too large for in-memory buffer: " + e.getMessage());
			spillSingleRecord(key, value);
			return;
		}
	}

	@SuppressWarnings("unchecked")
	public synchronized void collect(K key, V value)
	throws IOException {
		reporter.progress();
		if (key.getClass() != keyClass) {
			throw new IOException("Type mismatch in key from map: expected "
					+ keyClass.getName() + ", recieved "
					+ key.getClass().getName());
		}
		if (value.getClass() != valClass) {
			throw new IOException("Type mismatch in value from map: expected "
					+ valClass.getName() + ", recieved "
					+ value.getClass().getName());
		}
		if (sortSpillException != null) {
			throw (IOException)new IOException("Spill failed"
			).initCause(sortSpillException);
		}
		try {
			// serialize key bytes into buffer
			int keystart = bufindex;
			keySerializer.serialize(key);
			if (bufindex < keystart) {
				// wrapped the key; reset required
				bb.reset();
				keystart = 0;
			}
			// serialize value bytes into buffer
			int valstart = bufindex;
			valSerializer.serialize(value);
			int valend = bb.markRecord();

			if (keystart == bufindex) {
				// if emitted records make no writes, it's possible to wrap
				// accounting space without notice
				bb.write(new byte[0], 0, 0);
			}

			int partition = partitioner.getPartition(key, value, partitions);
			if (partition < 0 || partition >= partitions) {
				throw new IOException("Illegal partition for " + key + " (" +
						partition + ")");
			}

			// update accounting info
			int ind = kvindex * ACCTSIZE;
			kvoffsets[kvindex] = ind;
			kvindices[ind + PARTITION] = partition;
			kvindices[ind + KEYSTART] = keystart;
			kvindices[ind + VALSTART] = valstart;
			kvindex = (kvindex + 1) % kvoffsets.length;
		} catch (MapBufferTooSmallException e) {
			LOG.info("Record too large for in-memory buffer: " + e.getMessage());
			spillSingleRecord(key, value);
			return;
		}

	}

	/**
	 * Compare logical range, st i, j MOD offset capacity.
	 * Compare by partition, then by key.
	 * @see IndexedSortable#compare
	 */
	public int compare(int i, int j) {
		final int ii = kvoffsets[i % kvoffsets.length];
		final int ij = kvoffsets[j % kvoffsets.length];
		// sort by partition
		if (kvindices[ii + PARTITION] != kvindices[ij + PARTITION]) {
			return kvindices[ii + PARTITION] - kvindices[ij + PARTITION];
		}
		// sort by key
		return comparator.compare(kvbuffer,
				kvindices[ii + KEYSTART],
				kvindices[ii + VALSTART] - kvindices[ii + KEYSTART],
				kvbuffer,
				kvindices[ij + KEYSTART],
				kvindices[ij + VALSTART] - kvindices[ij + KEYSTART]);
	}

	/**
	 * Swap logical indices st i, j MOD offset capacity.
	 * @see IndexedSortable#swap
	 */
	public void swap(int i, int j) {
		i %= kvoffsets.length;
		j %= kvoffsets.length;
		int tmp = kvoffsets[i];
		kvoffsets[i] = kvoffsets[j];
		kvoffsets[j] = tmp;
	}

	/**
	 * Inner class managing the spill of serialized records to disk.
	 */
	protected class BlockingBuffer extends DataOutputStream {

		public BlockingBuffer() {
			this(new Buffer());
		}

		private BlockingBuffer(OutputStream out) {
			super(out);
		}

		/**
		 * Mark end of record. Note that this is required if the buffer is to
		 * cut the spill in the proper place.
		 */
		public int markRecord() {
			bufmark = bufindex;
			return bufindex;
		}

		/**
		 * Set position from last mark to end of writable buffer, then rewrite
		 * the data between last mark and kvindex.
		 * This handles a special case where the key wraps around the buffer.
		 * If the key is to be passed to a RawComparator, then it must be
		 * contiguous in the buffer. This recopies the data in the buffer back
		 * into itself, but starting at the beginning of the buffer. Note that
		 * reset() should <b>only</b> be called immediately after detecting
		 * this condition. To call it at any other time is undefined and would
		 * likely result in data loss or corruption.
		 * @see #markRecord()
		 */
		protected synchronized void reset() throws IOException {
			// spillLock unnecessary; If spill wraps, then
			// bufindex < bufstart < bufend so contention is impossible
			// a stale value for bufstart does not affect correctness, since
			// we can only get false negatives that force the more
			// conservative path
			int headbytelen = bufvoid - bufmark;
			bufvoid = bufmark;
			if (bufindex + headbytelen < bufstart) {
				System.arraycopy(kvbuffer, 0, kvbuffer, headbytelen, bufindex);
				System.arraycopy(kvbuffer, bufvoid, kvbuffer, 0, headbytelen);
				bufindex += headbytelen;
			} else {
				byte[] keytmp = new byte[bufindex];
				System.arraycopy(kvbuffer, 0, keytmp, 0, bufindex);
				bufindex = 0;
				out.write(kvbuffer, bufmark, headbytelen);
				out.write(keytmp);
			}
		}
	}

	public class Buffer extends OutputStream {
		private final byte[] scratch = new byte[1];

		@Override
		public synchronized void write(int v)
		throws IOException {
			scratch[0] = (byte)v;
			write(scratch, 0, 1);
		}

		/**
		 * Attempt to write a sequence of bytes to the collection buffer.
		 * This method will block if the spill thread is running and it
		 * cannot write.
		 * @throws MapBufferTooSmallException if record is too large to
		 *    deserialize into the collection buffer.
		 */
		@Override
		public synchronized void write(byte b[], int off, int len)
		throws IOException {
			boolean kvfull = false;
			boolean buffull = false;
			boolean wrap = false;
			synchronized(spillLock) {
				do {
					if (sortSpillException != null) {
						throw (IOException)new IOException("Spill failed"
						).initCause(sortSpillException);
					}

					// sufficient accounting space?
					final int kvnext = (kvindex + 1) % kvoffsets.length;
					kvfull = kvnext == kvstart;
					// sufficient buffer space?
					if (bufstart <= bufend && bufend <= bufindex) {
						buffull = bufindex + len > bufvoid;
						wrap = (bufvoid - bufindex) + bufstart > len;
					} else {
						// bufindex <= bufstart <= bufend
						// bufend <= bufindex <= bufstart
						wrap = false;
						buffull = bufindex + len > bufstart;
					}

					if (kvstart == kvend) {
						// spill thread not running
						if (kvend != kvindex) {
							// we have records we can spill
							final boolean kvsoftlimit = (kvnext > kvend)
							? kvnext - kvend > softRecordLimit
									: kvend - kvnext <= kvoffsets.length - softRecordLimit;
							final boolean bufsoftlimit = (bufindex > bufend)
							? bufindex - bufend > softBufferLimit
									: bufend - bufindex < bufvoid - softBufferLimit;
							if (kvsoftlimit || bufsoftlimit || (buffull && !wrap)) {
								LOG.info("Spilling map output: buffer full = " + bufsoftlimit+
										" and record full = " + kvsoftlimit);
								LOG.info("bufstart = " + bufstart + "; bufend = " + bufmark +
										"; bufvoid = " + bufvoid);
								LOG.info("kvstart = " + kvstart + "; kvend = " + kvindex +
										"; length = " + kvoffsets.length);
								/* already handled in doSpill() call.
								kvend = kvindex;
								bufend = bufmark;
								 */
								// TODO No need to recreate this thread every time
								spillThread.doSpill();
							}
						} else if (buffull && !wrap) {
							// We have no buffered records, and this record is too large
							// to write into kvbuffer. We must spill it directly from
							// collect
							final int size = ((bufend <= bufindex)
									? bufindex - bufend
											: (bufvoid - bufend) + bufindex) + len;
							bufstart = bufend = bufindex = bufmark = 0;
							kvstart = kvend = kvindex = 0;
							bufvoid = kvbuffer.length;
							throw new MapBufferTooSmallException(size + " bytes");
						}
					}

					if (kvfull || (buffull && !wrap)) {
						while (kvstart != kvend) {
							reporter.progress();
							try {
								spillLock.wait();
							} catch (InterruptedException e) {
								throw (IOException)new IOException(
										"Buffer interrupted while waiting for the writer"
								).initCause(e);
							}
						}
					}
				} while (kvfull || (buffull && !wrap));
			}
			// here, we know that we have sufficient space to write
			if (buffull) {
				final int gaplen = bufvoid - bufindex;
				System.arraycopy(b, off, kvbuffer, bufindex, gaplen);
				len -= gaplen;
				off += gaplen;
				bufindex = 0;
			}
			System.arraycopy(b, off, kvbuffer, bufindex, len);
			bufindex += len;
		}
	}

	public synchronized void snapshot() throws IOException {
		if (pipeline) throw new IOException("JBuffer can't snapshot with pipelining turned on.");
		LOG.debug("JBuffer " + taskid + " performing snaphsot. progress " + getProgress().get());
		spillThread.forceSpill();
		OutputFile outputFile = merger.mergeSnapshot();
		umbilical.output(outputFile);
	}

	public synchronized void force() throws IOException {
		spillThread.forceSpill();
	}

	public ValuesIterator<K, V> iterator() throws IOException {
		Path finalOutputFile = outputHandle.getOutputFile(this.taskid);
		RawKeyValueIterator kvIter = new FSMRResultIterator(this.localFs, finalOutputFile);
		return new ValuesIterator<K, V>(kvIter, comparator, keyClass, valClass, job, reporter);
	}

	public synchronized void reset(boolean restart) throws IOException {
		bufindex = 0;
		bufvoid  = kvbuffer.length;
		kvstart = kvend = kvindex = 0;  
		bufstart = bufend = bufindex = bufmark = 0; 

		this.progress = new Progress();

		if (restart) {
			spillThread.close();
			mergeThread.close();
			
			/* reset buffer variables. */
			for (JBufferFile spill : spills) {
				spill.delete();
			}
			spills.clear();

			/* restart threads. */
			this.spillThread = new SpillThread();
			this.spillThread.setDaemon(true);
			this.spillThread.start();

			this.mergeThread = new MergeThread();
			this.mergeThread.setDaemon(true);
			if (!taskid.isMap()) this.mergeThread.start();
		}
	}

	public synchronized OutputFile close() throws IOException {  
		LOG.debug("JBuffer: closed called at progress " + getProgress().get());
		OutputFile finalOutput = flush();
		reset(false);
		return finalOutput;
	}

	public synchronized void free() {
		kvbuffer = null;
	}

	public void spill(Path data, Path index, SpillOp op) throws IOException {
		if (op == SpillOp.NOOP) {
			return;
		}
		
		Path dataFile = null;
		Path indexFile = null;
		synchronized (mergeLock) {
			dataFile  = outputHandle.getSpillFileForWrite(this.taskid, spills.size(), 1096);
			indexFile = outputHandle.getSpillIndexFileForWrite(this.taskid, spills.size(), partitions * MAP_OUTPUT_INDEX_RECORD_LENGTH);

			if (op == SpillOp.COPY) {
				localFs.copyFromLocalFile(data, dataFile);
				localFs.copyFromLocalFile(index, indexFile);
			}
			else if (op == SpillOp.RENAME) {
				if (!localFs.rename(data, dataFile)) {
					throw new IOException("JBuffer::spill -- unable to rename " + data + " to " + dataFile);
				}
				if (!localFs.rename(index, indexFile)) {
					throw new IOException("JBuffer::spill -- unable to rename " + index + " to " + indexFile);
				}
			}
			spills.add(new JBufferFile(dataFile, indexFile));

			mergeLock.notifyAll();
		}
	}

	private void writeEmptyOutput(FSDataOutputStream dataOut, FSDataOutputStream indexOut) throws IOException {
		//create dummy output
		for (int i = 0; i < partitions; i++) {
			long segmentStart = dataOut.getPos();
			IFile.Writer<K, V> writer = new IFile.Writer<K, V>(job, dataOut,  keyClass, valClass, codec);
			writer.close();
			writeIndexRecord(indexOut, dataOut, segmentStart, writer);
		}
	}
	
	private OutputFile flush() throws IOException {
		long timestamp = System.currentTimeMillis();
		LOG.debug("Begin final flush");

		timestamp = System.currentTimeMillis();
		mergeThread.close();
		LOG.debug("merge thread closed. total time = " + (System.currentTimeMillis() - timestamp) + " ms.");

		timestamp = System.currentTimeMillis();
		spillThread.close();
		LOG.debug("spill thread closed. total time = " + (System.currentTimeMillis() - timestamp) + " ms.");

		timestamp = System.currentTimeMillis();
		OutputFile finalOut = merger.mergeFinal();
		LOG.debug("Final merge done. total time = " + (System.currentTimeMillis() - timestamp) + " ms.");
		return finalOut;
	}

	private float sortAndSpill() throws IOException {
		//approximate the length of the output file to be the length of the
		//buffer + header lengths for the partitions
		synchronized (mergeLock) {
			long size = (bufend >= bufstart
					? bufend - bufstart
							: (bufvoid - bufend) + bufstart) +
							partitions * APPROX_HEADER_LENGTH;
			FSDataOutputStream out = null;
			FSDataOutputStream indexOut = null;
			try {
				// create spill file
				Path filename = outputHandle.getSpillFileForWrite(this.taskid, spills.size(), size);
				if (localFs.exists(filename)) {
					throw new IOException("JBuffer::sortAndSpill -- spill file exists! " + filename);
				}

				out = localFs.create(filename, false);
				if (out == null ) throw new IOException("Unable to create spill file " + filename);
				// create spill index
				Path indexFilename = outputHandle.getSpillIndexFileForWrite(
						this.taskid, spills.size(), partitions * MAP_OUTPUT_INDEX_RECORD_LENGTH);
				indexOut = localFs.create(indexFilename, false);

				final int endPosition = (kvend > kvstart)
				? kvend
						: kvoffsets.length + kvend;
				sorter.sort(JBuffer.this, kvstart, endPosition, reporter);
				int spindex = kvstart;
				InMemValBytes value = new InMemValBytes();
				for (int i = 0; i < partitions; ++i) {
					IFile.Writer<K, V> writer = null;
					try {
						long segmentStart = out.getPos();
						writer = new IFile.Writer<K, V>(job, out, keyClass, valClass, codec);

						if (null == combinerClass) {
							// spill directly
							DataInputBuffer key = new DataInputBuffer();
							while (spindex < endPosition
									&& kvindices[kvoffsets[spindex
									                       % kvoffsets.length]
									                       + PARTITION] == i) {
								final int kvoff = kvoffsets[spindex % kvoffsets.length];
								getVBytesForOffset(kvoff, value);
								key.reset(kvbuffer, kvindices[kvoff + KEYSTART], 
										(kvindices[kvoff + VALSTART] - kvindices[kvoff + KEYSTART]));

								writer.append(key, value);
								++spindex;
							}
						} else {
							int spstart = spindex;
							while (spindex < endPosition
									&& kvindices[kvoffsets[spindex % kvoffsets.length] + PARTITION] == i) {
								++spindex;
							}
							// Note: we would like to avoid the combiner if
							// we've fewer
							// than some threshold of records for a partition
							if (spstart != spindex) {
								CombineOutputCollector combineCollector = new CombineOutputCollector();
								combineCollector.setWriter(writer);

								RawKeyValueIterator kvIter = new MRResultIterator(spstart, spindex);
								combineAndSpill(combineCollector, kvIter);
							}
						}

						// close the writer
						writer.close();

						// write the index as <offset, raw-length,
						// compressed-length>
						LOG.info("JBuffer: " + taskid + " spill " + spills.size() + " partition " + i + " segment start " + segmentStart);
						writeIndexRecord(indexOut, out, segmentStart, writer);
						writer = null;
					} finally {
						if (null != writer) {
							writer.close();
							writer = null;
						}
					}
				}
				JBufferFile spill = new JBufferFile(filename, indexFilename);
				LOG.info("Finished spill " + spills.size());
				spills.add(spill);
				return size > 0 ? (float) out.getPos() / (float) size : 0f;
			} finally {
				if (out != null) out.close();
				if (indexOut != null) indexOut.close();
			}
		}
	}

	private void spillSingleRecord(final DataInputBuffer key, final DataInputBuffer value)  throws IOException {
		// TODO this right
		Deserializer<K> keyDeserializer = serializationFactory.getDeserializer(keyClass);
		Deserializer<V> valDeserializer = serializationFactory.getDeserializer(valClass);
		keyDeserializer.open(key);
		valDeserializer.open(value);
		K k = keyDeserializer.deserialize(null);
		V v = valDeserializer.deserialize(null);
		spillSingleRecord(k, v);
	}

	/**
	 * Handles the degenerate case where serialization fails to fit in
	 * the in-memory buffer, so we must spill the record from collect
	 * directly to a spill file. Consider this "losing".
	 */
	@SuppressWarnings("unchecked")
	private void spillSingleRecord(final K key, final V value)  throws IOException {
		synchronized (mergeLock) {
			long size = kvbuffer.length + partitions * APPROX_HEADER_LENGTH;
			FSDataOutputStream out = null;
			FSDataOutputStream indexOut = null;
			final int partition = partitioner.getPartition(key, value, partitions);
			try {
				// create spill file
				Path filename = outputHandle.getSpillFileForWrite(this.taskid, spills.size(), size);
				out = localFs.create(filename);
				// create spill index
				Path indexFilename = outputHandle.getSpillIndexFileForWrite(
						this.taskid, spills.size(), partitions * MAP_OUTPUT_INDEX_RECORD_LENGTH);
				indexOut = localFs.create(indexFilename);
				// we don't run the combiner for a single record
				for (int i = 0; i < partitions; ++i) {
					IFile.Writer writer = null;
					try {
						long segmentStart = out.getPos();
						// Create a new codec, don't care!
						writer = new IFile.Writer(job, out, keyClass, valClass, codec);

						if (i == partition) {
							writer.append(key, value);
						}
						writer.close();

						// index record
						writeIndexRecord(indexOut, out, segmentStart, writer);
					} catch (IOException e) {
						if (null != writer) writer.close();
						throw e;
					}
				}
				spills.add(new JBufferFile(filename, indexFilename));
			} finally {
				if (out != null) out.close();
				if (indexOut != null) indexOut.close();
			}
		}
	}

	/**
	 * Given an offset, populate vbytes with the associated set of
	 * deserialized value bytes. Should only be called during a spill.
	 */
	private void getVBytesForOffset(int kvoff, InMemValBytes vbytes) {
		final int nextindex = (kvoff / ACCTSIZE ==
			(kvend - 1 + kvoffsets.length) % kvoffsets.length)
			? bufend
					: kvindices[(kvoff + ACCTSIZE + KEYSTART) % kvindices.length];
		int vallen = (nextindex >= kvindices[kvoff + VALSTART])
		? nextindex - kvindices[kvoff + VALSTART]
		                        : (bufvoid - kvindices[kvoff + VALSTART]) + nextindex;
		vbytes.reset(kvbuffer, kvindices[kvoff + VALSTART], vallen);
	}

	@SuppressWarnings("unchecked")
	private void combineAndSpill(CombineOutputCollector<K, V> combineCollector, RawKeyValueIterator kvIter) throws IOException {
		Reducer combiner =
			(Reducer)ReflectionUtils.newInstance(combinerClass, job);
		try {
			ValuesIterator values = new ValuesIterator(
					kvIter, comparator, keyClass, valClass, job, reporter);
			while (values.more()) {
				combiner.reduce(values.getKey(), values, combineCollector, reporter);
				values.nextKey();
				// indicate we're making progress
				reporter.progress();
			}
		} finally {
			combiner.close();
		}
	}

	/**
	 * Inner class wrapping valuebytes, used for appendRaw.
	 */
	protected class InMemValBytes extends DataInputBuffer {
		private byte[] buffer;
		private int start;
		private int length;

		public void reset(byte[] buffer, int start, int length) {
			this.buffer = buffer;
			this.start = start;
			this.length = length;

			if (start + length > bufvoid) {
				this.buffer = new byte[this.length];
				final int taillen = bufvoid - start;
				System.arraycopy(buffer, start, this.buffer, 0, taillen);
				System.arraycopy(buffer, 0, this.buffer, taillen, length-taillen);
				this.start = 0;
			}

			super.reset(this.buffer, this.start, this.length);
		}
	}

	protected class MRResultIterator implements RawKeyValueIterator {
		private final DataInputBuffer keybuf = new DataInputBuffer();
		private final InMemValBytes vbytes = new InMemValBytes();
		private final int end;
		private int current;
		public MRResultIterator(int start, int end) {
			this.end = end;
			current = start - 1;
		}
		public boolean next() throws IOException {
			return ++current < end;
		}
		public DataInputBuffer getKey() throws IOException {
			final int kvoff = kvoffsets[current % kvoffsets.length];
			keybuf.reset(kvbuffer, kvindices[kvoff + KEYSTART],
					kvindices[kvoff + VALSTART] - kvindices[kvoff + KEYSTART]);
			return keybuf;
		}
		public DataInputBuffer getValue() throws IOException {
			getVBytesForOffset(kvoffsets[current % kvoffsets.length], vbytes);
			return vbytes;
		}
		public Progress getProgress() {
			return null;
		}
		public void close() { }
	}

	protected class FSMRResultIterator implements RawKeyValueIterator {
		private IFile.Reader reader;
		private DataInputBuffer key = new DataInputBuffer();
		private DataInputBuffer value = new DataInputBuffer();
		private Progress progress = new Progress();

		public FSMRResultIterator(FileSystem localFS, Path path) throws IOException {
			this.reader = new IFile.Reader<K, V>(job, localFS, path, codec);
		}

		@Override
		public void close() throws IOException {
			this.reader.close();
		}

		@Override
		public DataInputBuffer getKey() throws IOException {
			return this.key;
		}

		@Override
		public Progress getProgress() {
			try {
				float score = reader.getPosition() / (float) reader.getLength();
				progress.set(score);
			} catch (IOException e) {
				e.printStackTrace();
			}
			return progress;
		}

		@Override
		public DataInputBuffer getValue() throws IOException {
			return this.value;
		}

		@Override
		public boolean next() throws IOException {
			return this.reader.next(key, value);
		}
	}

	private void writeIndexRecord(FSDataOutputStream indexOut, 
			FSDataOutputStream out, long start, 
			IFile.Writer<K, V> writer) 
	throws IOException {
		//when we write the offset/decompressed-length/compressed-length to  
		//the final index file, we write longs for both compressed and 
		//decompressed lengths. This helps us to reliably seek directly to 
		//the offset/length for a partition when we start serving the 
		//byte-ranges to the reduces. We probably waste some space in the 
		//file by doing this as opposed to writing VLong but it helps us later on.
		// index record: <offset, raw-length, compressed-length> 
		//StringBuffer sb = new StringBuffer();
		indexOut.writeLong(start);
		indexOut.writeLong(writer.getRawLength());
		long segmentLength = out.getPos() - start;
		indexOut.writeLong(segmentLength);
	}

	/**
	 * Exception indicating that the allocated sort buffer is insufficient
	 * to hold the current record.
	 */
	@SuppressWarnings("serial")
	private static class MapBufferTooSmallException extends IOException {
		public MapBufferTooSmallException(String s) {
			super(s);
		}
	}
} // MapOutputBuffer
