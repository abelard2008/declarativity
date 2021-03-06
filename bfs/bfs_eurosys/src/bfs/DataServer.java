package bfs;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;

public class DataServer extends Thread {
    /*
     * A DataWorker instance handles a single client connection, and runs in a
     * separate thread from the DataServer.
     */
    private class DataWorker extends Thread {
        private SocketChannel channel;
        private String clientAddr;
        private DataInputStream in;
        private DataOutputStream out;
        private volatile boolean inShutdown;

        DataWorker(SocketChannel channel, ThreadGroup group, int id) throws IOException {
        	super(group, "DataWorker-" + id);
            channel.configureBlocking(true);
            this.channel = channel;
            Socket socket = channel.socket();
            this.clientAddr = socket.toString();
            this.in = new DataInputStream(socket.getInputStream());
            this.out = new DataOutputStream(socket.getOutputStream());
            this.inShutdown = false;
        }

        @Override
        public void run() {
            while (true) {
                try {
                    try {
                        readCommand();
                    } catch (EOFException e) {
                        this.channel.close();
                        System.out.println("got EOF from " + this.clientAddr);
                        break;
                    }
                } catch (IOException e) {
                	if (this.inShutdown)
                		break;
                    throw new RuntimeException(e);
                }
            }
        }

        public void shutdown() {
        	if (this.inShutdown)
        		return;

        	this.inShutdown = true;
        	// XXX: There doesn't appear to be a reasonable way to interrupt a
			// blocking read with old-style Java I/O (!). Therefore, just close
			// the socket, and catch/ignore the IOException in run().
        	try {
        		this.channel.close();
        	} catch (IOException e) {
        		throw new RuntimeException(e);
        	}

        	try {
        		join();
        	} catch (InterruptedException e) {
        		throw new IllegalStateException("unexpected interrupt", e);
        	}
        }

        private void readCommand() throws IOException {
            byte opCode = this.in.readByte();

            switch (opCode) {
            case DataProtocol.READ_OPERATION:
                doReadOperation();
                break;
            case DataProtocol.WRITE_OPERATION:
                doWriteOperation();
                break;
            case DataProtocol.DELETE_OPERATION:
                doDeleteOperation();
                break;
            default:
                throw new IOException("Unrecognized opcode: " + opCode + " from " + this.clientAddr) ;
            }
        }

        private void doWriteOperation() throws IOException {
        	int chunkId = this.in.readInt();
        	List<String> path = readHeaders();
        	int dataLength = this.in.readInt();

        	long startTime = System.currentTimeMillis();
        	File chunkFile = createChunkFile(chunkId);
        	FileChannel fc = new FileOutputStream(chunkFile).getChannel();
        	long offset = 0;
        	while (true) {
        		long nread = fc.transferFrom(this.channel, offset, dataLength - offset);
        		offset += nread;
        		if (offset == dataLength)
        			break;
        		else if (offset > dataLength)
        			throw new IllegalStateException();
        	}
        	fc.close();
        	System.out.println("Wrote chunk file " + chunkId + " (length " +
        			           chunkFile.length() + ") in " +
        			           (System.currentTimeMillis() - startTime) + " msec");

        	if (chunkFile.length() != dataLength)
        		throw new RuntimeException("Unexpected file length: expected " +
        				                   dataLength + ", got " + chunkFile.length());

        	startTime = System.currentTimeMillis();
        	createCRCFile(chunkId, getFileChecksum(chunkFile));
        	System.out.println("Wrote CRC file for " + chunkId + " in " +
			           (System.currentTimeMillis() - startTime) + " msec");

        	// we are not pipelining yet.
        	if (path.size() > 0) {
        		System.out.println("Path size was " + path.size());
        		copyToNext(chunkFile, chunkId, path);
        	}
        }

        private void copyToNext(File f, int chunkId, List<String> path) throws IOException {
        	DataConnection conn = new DataConnection(path);
        	conn.sendRoutingData(chunkId);

        	FileChannel fc = new FileInputStream(f).getChannel();
        	conn.write(fc, (int) f.length());
        	fc.close();
        	conn.close();
        }

        private void doDeleteOperation() throws IOException {
            int chunkId = this.in.readInt();
			File victim = getChunkFile(chunkId);
			victim.delete();
        }

        private List<String> readHeaders() throws IOException {
            // XXX: clean this up later
        	String[] path = null;
        	int sourceRouteListLen = this.in.readInt();
        	if (sourceRouteListLen > 0) {
        		path = new String[sourceRouteListLen + 1];
        		int i = 0;
        		path[0] = "";
        		for (char b = this.in.readChar(); b != ';' && i < sourceRouteListLen; b = this.in.readChar()) {
        			if (b == '|') {
        				i++;
        				path[i] = "";
        			} else {
        				path[i] = path[i] + b;
        			}
        		}
        	} else {
        		if (this.in.readChar() != ';') {
        			throw new RuntimeException("invalid header");
        		}
        	}

        	List<String> ret = new LinkedList<String>();
        	for (int i = 0; i < sourceRouteListLen; i++) {
        		ret.add(path[i]);
        	}
        	return ret;
        }

        private void doReadOperation() throws IOException {
            int chunkId = this.in.readInt();
            File chunkFile = getChunkFile(chunkId);

            // If we don't have the chunk, return failure to the client
            if (chunkFile == null) {
            	this.out.writeBoolean(false);
            	return;
            }
            // Otherwise, return success, then the length + contents
            this.out.writeBoolean(true);

            int fileSize = (int) chunkFile.length();
            this.out.writeInt(fileSize);

            long cksum = getFileChecksum(chunkFile);
            long expectedCksum = getCRCFromFile(chunkId);
            if (cksum != expectedCksum) {
                // what should we do? presumably, signal the master to delete
				// the bad chunk
                throw new RuntimeException("file checksums didn't match");
            }

            FileChannel fc = new FileInputStream(chunkFile).getChannel();
            long offset = 0;
            while (true) {
            	long nwrite = fc.transferTo(offset, fileSize - offset, this.channel);
            	offset += nwrite;
            	if (offset == fileSize)
            		break;
            	else if (offset > fileSize)
            		throw new IllegalStateException();
            }
            fc.close();
        }
    }

    private File fsRoot;
    private ThreadGroup workers;
    private int nextWorkerId;
    private ServerSocketChannel listener;
    private volatile boolean inShutdown;

    DataServer(int port, File fsRoot) {
        this.fsRoot = fsRoot;
        this.workers = new ThreadGroup("DataWorkers");
        this.nextWorkerId = 0;
        this.inShutdown = false;

        try {
            this.listener = ServerSocketChannel.open();
            ServerSocket serverSocket = this.listener.socket();
            serverSocket.setReuseAddress(true);

            InetSocketAddress listenAddr = new InetSocketAddress(port);
            serverSocket.bind(listenAddr);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void shutdown() {
    	if (this.inShutdown)
    		return;

    	// Notify the DataServer thread that it should shutdown; we stop
		// accepting new connections at this point
    	this.inShutdown = true;
    	interrupt();
    	try {
    		join();
    	} catch (InterruptedException e) {
    		throw new IllegalStateException("unexpected interrupt", e);
    	}

    	// Interrupt each active worker thread and wait for it to exit
        Thread[] threadList = new Thread[this.workers.activeCount()];
        this.workers.enumerate(threadList);
        for (Thread t : threadList) {
        	DataWorker dw = (DataWorker) t;
        	dw.shutdown();
        }
        this.workers.destroy();
    }

    @Override
    public void run() {
		while (true) {
			try {
				if (this.inShutdown) {
					System.out.println("Got shutdown request");

					// shutdown() notifies us that we should shutdown by setting
					// the "inShutdown" field, and then calling interrupt(). If
					// we're inside accept() at the time, interrupt() auto-closes
					// the socket; otherwise, we need to do so by hand.
					if (this.listener.isOpen())
						this.listener.close();
					break;
				}

				SocketChannel channel = this.listener.accept();
				DataWorker dw = new DataWorker(channel, this.workers,
						                       this.nextWorkerId++);
				dw.start();
			} catch (ClosedByInterruptException e) {
				if (!this.inShutdown)
					throw new IllegalStateException("unexpected interrupt", e);
				// Otherwise, just rerun the loop and then exit
			} catch (ClosedChannelException e) {
				throw new RuntimeException(e);
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
	}

    public void createCRCFile(int chunkId, long checksum) throws IOException {
        String filename = this.fsRoot + File.separator + "checksums" + File.separator
                + chunkId + ".cksum";
        File newf = new File(filename);
        if (!newf.createNewFile()) {
        	throw new RuntimeException("Failed to create fresh file " + filename);
        }

        DataOutputStream fos = new DataOutputStream(new FileOutputStream(newf));
        fos.writeLong(checksum);
        fos.close();
    }

    public long getCRCFromFile(int chunkId) throws IOException {
        String filename = this.fsRoot + File.separator + "checksums" + File.separator
                + chunkId + ".cksum";
        File newf = new File(filename);
        if (!newf.exists())
        	throw new RuntimeException("Failed to find checksum "+ filename);

        DataInputStream dis = new DataInputStream(new FileInputStream(newf));
        long cLong = dis.readLong();
        dis.close();

        return cLong;
    }

    private long getFileChecksum(File file) throws IOException {
    	FileInputStream s = new FileInputStream(file);
    	CheckedInputStream check = new CheckedInputStream(s, new CRC32());
    	BufferedInputStream in = new BufferedInputStream(check);
    	byte[] tmpBuf = new byte[8192];
    	while (in.read(tmpBuf, 0, tmpBuf.length) != -1) {

    	}
    	return check.getChecksum().getValue();
    }

    public File createChunkFile(int chunkId) throws IOException {
        String filename = this.fsRoot + File.separator + "chunks" + File.separator
                + chunkId;
        File newf = new File(filename);
        if (!newf.createNewFile()) {
        	File d = new File(this.fsRoot + File.separator + "chunks");
        	for (String f : d.list()) {
        		System.out.println("\tFILE: " + f);
        	}
        	throw new RuntimeException("Failed to create fresh file " + filename);
        }

        return newf;
    }

    public File getChunkFile(int chunkId) {
        String filename = this.fsRoot + File.separator + "chunks" + File.separator
                + chunkId;
        File file = new File(filename);
        if (!file.exists())
        	return null;

        if (!file.isFile())
            throw new RuntimeException("Chunk file is not a regular file: " + file);

        return file;
    }

	/**
	 * This function is periodically invoked by the Overlog code; it returns a
	 * list of the chunk files stored at this data node. Before returning a
	 * chunk file, we check that the checksum file for that chunk already
	 * exists. This avoids a race condition: we don't want to let Overlog-land
	 * know that a file exists until it has been completely written to disk.
	 * Since we write the checksum file after we've finished writing the file
	 * itself, this check avoids the race.
	 */
	public static List<File> dirListing(File dir) {
		File fDir = new File(dir, "chunks");
		File cDir = new File(dir, "checksums");

		List<File> ret = new ArrayList<File>();

		for (File f : fDir.listFiles()) {
			File csum = new File(cDir, f.getName() + ".cksum");
			if (csum.exists()) {
				ret.add(f);
			}
		}

		return ret;
	}
}
