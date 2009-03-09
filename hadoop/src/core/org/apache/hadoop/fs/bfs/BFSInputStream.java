package org.apache.hadoop.fs.bfs;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.Set;

import org.apache.hadoop.fs.FSInputStream;

import bfs.BFSChunkInfo;
import bfs.BFSClient;
import bfs.BFSFileInfo;
import bfs.Conf;
import bfs.DataProtocol;

public class BFSInputStream extends FSInputStream {
	private BFSClient bfs;
	private String path;
	private List<BFSChunkInfo> chunkList;
	private boolean isClosed;
	private boolean atEOF;
	private ByteBuffer buf;

	// Byte-wise offset into the logical file contents
	private long position;

	// Index into "chunkList" identifying the current chunk we're positioned at.
	// These fields are updated by updatePosition().
	private int currentChunkIdx;
	private BFSChunkInfo currentChunk;
	private Set<String> chunkLocations;

	public BFSInputStream(String path, BFSClient bfs) throws IOException {
		this.bfs = bfs;
		this.path = path;
		this.isClosed = false;
		this.atEOF = false;
		this.buf = ByteBuffer.allocate(Conf.getChunkSize());
		this.chunkList = bfs.getChunkList(path);
        long length = 0;
        for (BFSChunkInfo cInfo : this.chunkList) {
            length += cInfo.getLength();
        }
        System.out.println("BFSInputStream#new(): length = " + length);
		updatePosition(0);
	}

	@Override
	public synchronized long getPos() throws IOException {
		return this.position;
	}

	@Override
	public synchronized void seek(long newPos) throws IOException {
		if (this.isClosed)
			throw new IOException("cannot seek on closed file");

		updatePosition(newPos);
	}

	private void updatePosition(long newPos) throws IOException {
        System.out.println("updatePosition(): oldPos = " + this.position + "; newPos = " + newPos);

		if (newPos < 0)
			throw new IOException("cannot seek to negative position");

		// Find the chunk index and offset containing the new byte position.
		// We recompute this from scratch on each call, which might be
		// expensive for very large files.
		long bytesLeft = newPos;
		int chunkIdx = 0;
		for (BFSChunkInfo chunk : this.chunkList) {
			if (bytesLeft < chunk.getLength())
				break;

			bytesLeft -= chunk.getLength();
			chunkIdx++;
		}

		if (bytesLeft > Conf.getChunkSize()) {
            this.atEOF = true;
            this.position = newPos;
            this.currentChunkIdx = chunkIdx;
        }

		int chunkOffset = (int) bytesLeft;

		if (chunkIdx == this.chunkList.size() || this.chunkList.isEmpty()) {
			this.atEOF = true;
		} else {
			if (chunkIdx != this.currentChunkIdx || this.currentChunk == null) {
				this.currentChunk = this.chunkList.get(chunkIdx);
				this.chunkLocations = bfs.getChunkLocations(this.path, this.currentChunk.getId());
				fetchChunkContent();
			}

			int chunkLen = this.currentChunk.getLength();
			if (chunkOffset > chunkLen) {
                this.atEOF = true;
            } else {
			    this.atEOF = false;
            }
		}

		this.currentChunkIdx = chunkIdx;
		this.position = newPos;
	}

	/**
	 * Read the content of "currentChunk" from one of the data nodes in
	 * "chunkLocations".
	 */
	private void fetchChunkContent() throws IOException {
		for (String loc : this.chunkLocations) {
			try {
				fetchChunkFromAddr(loc);
			} catch (IOException e) {
				continue; // Try another data node
			}

			// Read chunk successfully
			return;
		}

		// Failed to read chunk: all data nodes for this chunk are down
		throw new IOException("failed to read chunk: " + this.currentChunk);
	}

	private void fetchChunkFromAddr(String addr) throws IOException {
        String[] parts = addr.split(":");
        String host = parts[1];
        int controlPort = Integer.parseInt(parts[2]);
        int dataPort = Conf.findDataNodeDataPort(host, controlPort);

        SocketAddress sockAddr = new InetSocketAddress(host, dataPort);
        SocketChannel inChannel = SocketChannel.open();
        inChannel.configureBlocking(true);
        inChannel.connect(sockAddr);

        Socket sock = inChannel.socket();
        DataOutputStream dos = new DataOutputStream(sock.getOutputStream());
        DataInputStream dis = new DataInputStream(sock.getInputStream());

        dos.writeByte(DataProtocol.READ_OPERATION);
        dos.writeInt(this.currentChunk.getId());
        boolean success = dis.readBoolean();
        // If this data node didn't have the chunk, try another one.
        if (success == false)
        	throw new IOException();

        int length = dis.readInt();
        if (length != this.currentChunk.getLength())
        	throw new RuntimeException("expected length " +
        			                   this.currentChunk.getLength() +
        			                   ", data node copy's length is " +
        			                   length + ", chunk " +
        			                   this.currentChunk.getId());

        this.buf.clear();
        this.buf.limit(length);
        while (this.buf.hasRemaining()) {
        	int nread = inChannel.read(this.buf);
        	if (nread == -1)
        		throw new IOException("Unexpected EOF from data node");
        }

        sock.close();

        // We successfully read a chunk, so prepare the buffer to
        // be read from
        this.buf.rewind();
	}

	@Override
	public synchronized boolean seekToNewSource(long targetPos) throws IOException {
		if (this.isClosed)
			throw new IOException("cannot seek to new source on closed file");

		// This is a somewhat fraudulent implementation of this API,
		// but it should be sufficient
		updatePosition(targetPos);
		return true;
	}

	@Override
	public synchronized int read() throws IOException {
		byte[] tmpBuf = new byte[1];
		int result = read(tmpBuf, 0, 1);
		if (result == -1)
			return result;
		else
			return (int) tmpBuf[0];
	}

	@Override
	public synchronized int read(byte[] clientBuf, int offset, int len) throws IOException {
		if (this.isClosed)
			throw new IOException("cannot read from closed file");

		if (this.atEOF)
			return -1;

		// If we get here, there must be some data left to read in
		// the current chunk
		if (!this.buf.hasRemaining())
			throw new IllegalStateException();

		// If there's not enough data in the buffer to completely satisfy
		// the request, just read the remaining data. We then switch
		// to the next chunk in time for the next read() call.
		if (len > this.buf.remaining())
			len = this.buf.remaining();

		this.buf.get(clientBuf, offset, len);
		updatePosition(this.position + len);
		return len;
	}

	@Override
	public synchronized void close() throws IOException {
		if (this.isClosed)
			return;

		this.isClosed = true;
	}

	/*
	 * We don't support marks, for now.
	 */
	@Override
	public boolean markSupported() {
		return false;
	}

	@Override
	public void mark(int readLimit) {
		// Do nothing
	}

	@Override
	public void reset() throws IOException {
		throw new IOException("Mark not supported");
	}
}
