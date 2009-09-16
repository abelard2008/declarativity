package org.apache.hadoop.mapred.bufmanager;

import java.io.BufferedOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.DataInputBuffer;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableUtils;
import org.apache.hadoop.io.compress.CodecPool;
import org.apache.hadoop.io.compress.CompressionCodec;
import org.apache.hadoop.io.compress.Compressor;
import org.apache.hadoop.io.compress.DefaultCodec;
import org.apache.hadoop.io.serializer.Deserializer;
import org.apache.hadoop.io.serializer.SerializationFactory;
import org.apache.hadoop.mapred.IFile;
import org.apache.hadoop.mapred.IFileInputStream;
import org.apache.hadoop.mapred.IFileOutputStream;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.MapOutputFile;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.TaskAttemptID;
import org.apache.hadoop.mapred.IFile.Reader;
import org.apache.hadoop.mapred.IFile.Writer;
import org.apache.hadoop.mapred.Merger.Segment;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.util.ReflectionUtils;

public class BufferRequest<K extends Object, V extends Object> implements Comparable<BufferRequest>, Writable, Runnable {
	
	public boolean delivered = false;
	
	private TaskAttemptID taskid;
	
	private int partition;
	
	private String source = null;
	
	private InetSocketAddress sink = null;
	
	private FSDataOutputStream out = null;
	
	private JobConf conf = null;
	
	private FileSystem localFS = null;
	
	private int flushPoint = -1;
	
	private float datarate = 0f;
	
	public BufferRequest() {
	}
	
	public BufferRequest(TaskAttemptID taskid, int partition, String source, InetSocketAddress sink) {
		this.taskid    = taskid;
		this.partition = partition;
		this.source = source;
		this.sink   = sink;
	}
	
	@Override
	public int compareTo(BufferRequest o) {
		if (this.taskid.compareTo(o.taskid) != 0) {
			return this.taskid.compareTo(o.taskid);
		}
		else {
			return this.partition - o.partition;
		}
	}
	
	@Override
	public boolean equals (Object o) {
		if (o instanceof BufferRequest) {
			if (compareTo((BufferRequest) o) == 0) {
				/* and it's going to the same place */
				return this.sink.equals(((BufferRequest)o).sink);
			}
		}
		return false;
	}
	
	@Override
	public String toString() {
		return "BufferRequest: task " + taskid + " partition " + partition + " from " + source + " to " + sink;
	}
	
	public Integer partition() {
		return this.partition;
	}
	
	public int flushPoint() {
		return this.flushPoint;
	}
	
	public float datarate() {
		return this.datarate;
	}
	
	public void flush(FSDataInputStream indexIn, FSDataInputStream dataIn, int flushPoint, float progress) throws IOException {
		this.flushPoint = flushPoint;
		indexIn.seek(this.partition * JBuffer.MAP_OUTPUT_INDEX_RECORD_LENGTH);

		long segmentOffset    = indexIn.readLong();
		long rawSegmentLength = indexIn.readLong();
		long segmentLength    = indexIn.readLong();
				
		dataIn.seek(segmentOffset);
		flushFile(dataIn, segmentLength, progress);
	}
	
	public void flushFinal() throws IOException {
		MapOutputFile mapOutputFile = new MapOutputFile(this.taskid.getJobID());
		mapOutputFile.setConf(conf);

		Path finalOutputFile = mapOutputFile.getOutputFile(this.taskid);
		Path finalIndexFile = mapOutputFile.getOutputIndexFile(this.taskid);

		FSDataInputStream indexIn = localFS.open(finalIndexFile);
		indexIn.seek(this.partition * JBuffer.MAP_OUTPUT_INDEX_RECORD_LENGTH);
		long segmentOffset    = indexIn.readLong();
		long rawSegmentLength = indexIn.readLong();
		long segmentLength    = indexIn.readLong();
		indexIn.close();

		FSDataInputStream in = localFS.open(finalOutputFile);
		in.seek(segmentOffset);
		flushFile(in, segmentLength, 1.0f);
		
		in.close();
	}
	
	@Override
	public void run() {
		synchronized (this) {
			try {
				long begin = System.currentTimeMillis();
				flushFinal();
				System.err.println("FlushFinal " + taskid + " took " + (System.currentTimeMillis() - begin) + " ms.");
			} catch (IOException e) {
				e.printStackTrace();
			}
			finally {
				try {
					close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
	}
	
	
	public boolean open(JobConf conf, boolean snapshot) throws IOException {
		synchronized (this) {
			try { 
				this.conf = conf;
				this.localFS = FileSystem.getLocal(conf);
				this.out = connect(snapshot);
				return out != null;
			} catch (Throwable e) {
				e.printStackTrace();
				return false;
			}
		}
	}
	
	private FSDataOutputStream connect(boolean snapshot) throws IOException {
		try {
			Socket socket = new Socket();
			int connectionAttempts = this.conf.getInt("mapred.connection.attempts", 5);
			for (int i = 0; i < connectionAttempts && !socket.isConnected(); i++) {
				try {
					socket.connect(this.sink);
				} catch (java.net.ConnectException e) {
					System.err.println("BufferRequest: " + e);
				}
			}
			FSDataOutputStream out = new FSDataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
			this.taskid.write(out);
			out.writeBoolean(snapshot);
			out.flush();
			
			DataInputStream in = new DataInputStream(socket.getInputStream());
			boolean open = in.readBoolean();
			if (!open) {
				out.close();
				return null;
			}
			return out;
		} catch (IOException e) {
			e.printStackTrace();
			throw e;
		}
	}

	public TaskAttemptID taskid() {
		return this.taskid;
	}
	
	public String source() {
		return this.source;
	}
	
	public InetSocketAddress sink() {
		return this.sink;
	}
	
	@Override
	public void readFields(DataInput in) throws IOException {
		/* The taskid */
		this.taskid = new TaskAttemptID();
		this.taskid.readFields(in);
		
		/* The partition */
		this.partition = WritableUtils.readVInt(in);
		
		/* The address on which the map executes */
		this.source = WritableUtils.readString(in);
		
		/* The address on which the reduce executes */
		String sinkHost = WritableUtils.readString(in);
		int    sinkPort = WritableUtils.readVInt(in);
		this.sink = new InetSocketAddress(sinkHost, sinkPort);
	}

	@Override
	public void write(DataOutput out) throws IOException {
		if (this.source == null || this.sink == null)
			throw new IOException("No source/sink in request!");
		
		/* The taskid */
		this.taskid.write(out);
		
		/* The partition */
		WritableUtils.writeVInt(out, this.partition);
		
		/* The address on which the map executes */
		WritableUtils.writeString(out, this.source);
		
		/* The address on which the reduce executes */
		WritableUtils.writeString(out, this.sink.getHostName());
		WritableUtils.writeVInt(out, this.sink.getPort());
	}

	public void close() throws IOException {
		synchronized (this) {
			if (out != null) {
				out.flush();
				out.close();
				out = null;
			}
		}
	}
	
	private void flushFile(FSDataInputStream in, long length, float progress) throws IOException {
		if (length == 0 && progress < 1.0f) {
			return;
		}
		
		CompressionCodec codec = null;
		if (conf.getCompressMapOutput()) {
			Class<? extends CompressionCodec> codecClass =
				conf.getMapOutputCompressorClass(DefaultCodec.class);
			codec = (CompressionCodec) ReflectionUtils.newInstance(codecClass, conf);
		}
		Class <K> keyClass = (Class<K>)conf.getMapOutputKeyClass();
		Class <V> valClass = (Class<V>)conf.getMapOutputValueClass();

		long starttime = System.currentTimeMillis();
		out.writeLong(length);
		out.writeFloat(progress);

		IFile.Reader reader = new IFile.Reader<K, V>(conf, in, length, codec);
		IFile.Writer writer = new IFile.Writer<K, V>(conf, out,  keyClass, valClass, codec);

		DataInputBuffer key = new DataInputBuffer();
		DataInputBuffer value = new DataInputBuffer();
		while (reader.next(key, value)) {
			writer.append(key, value);
		}
		writer.close();
		
		if (length > 0) {
			long  stoptime = System.currentTimeMillis();
			float duration = 1.0f + (stoptime - starttime);
			float rate = ((float) length) / duration;
			datarate = (0.75f * rate) + (0.25f * datarate);
		}
	}

}
