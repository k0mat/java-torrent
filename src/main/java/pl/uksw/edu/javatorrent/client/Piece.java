
package pl.uksw.edu.javatorrent.client;

import pl.uksw.edu.javatorrent.client.peer.SharingPeer;
import pl.uksw.edu.javatorrent.client.storage.TorrentByteStorage;
import pl.uksw.edu.javatorrent.common.Torrent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.concurrent.Callable;

public class Piece implements Comparable<Piece> {

	private static final Logger logger =
		LoggerFactory.getLogger(Piece.class);

	private final TorrentByteStorage bucket;
	private final int index;
	private final long offset;
	private final long length;
	private final byte[] hash;
	private final boolean seeder;

	private volatile boolean valid;
	private int seen;
	private ByteBuffer data;
	public Piece(TorrentByteStorage bucket, int index, long offset,
		long length, byte[] hash, boolean seeder) {
		this.bucket = bucket;
		this.index = index;
		this.offset = offset;
		this.length = length;
		this.hash = hash;
		this.seeder = seeder;
		this.valid = false;
		this.seen = 0;
		this.data = null;
	}


	public boolean isValid() {
		return this.valid;
	}
	public int getIndex() {
		return this.index;
	}
	public long size() {
		return this.length;
	}
	public boolean available() {
		return this.seen > 0;
	}
	public void seenAt(SharingPeer peer) {
		this.seen++;
	}
	public void noLongerAt(SharingPeer peer) {
		this.seen--;
	}
	public synchronized boolean validate() throws IOException {
		if (this.seeder) {
			logger.trace("Skipping validation of {} (seeder mode).", this);
			this.valid = true;
			return true;
		}

		logger.trace("Validating {}...", this);
		this.valid = false;

		ByteBuffer buffer = this._read(0, this.length);
		byte[] data = new byte[(int)this.length];
		buffer.get(data);
		try {
			this.valid = Arrays.equals(Torrent.hash(data), this.hash);
		} catch (NoSuchAlgorithmException e) {
			this.valid = false;
		}

		return this.isValid();
	}
	private ByteBuffer _read(long offset, long length) throws IOException {
		if (offset + length > this.length) {
			throw new IllegalArgumentException("Piece#" + this.index +
				" overrun (" + offset + " + " + length + " > " +
				this.length + ") !");
		}

		// TODO: remove cast to int when large ByteBuffer support is
		// implemented in Java.
		ByteBuffer buffer = ByteBuffer.allocate((int)length);
		int bytes = this.bucket.read(buffer, this.offset + offset);
		buffer.rewind();
		buffer.limit(bytes >= 0 ? bytes : 0);
		return buffer;
	}

	public ByteBuffer read(long offset, int length)
		throws IllegalArgumentException, IllegalStateException, IOException {
		if (!this.valid) {
			throw new IllegalStateException("Attempting to read an " +
					"known-to-be invalid piece!");
		}

		return this._read(offset, length);
	}
	public synchronized void record(ByteBuffer block, int offset)
		throws IOException {
		if (this.data == null || offset == 0) {
			// TODO: remove cast to int when large ByteBuffer support is
			// implemented in Java.
			this.data = ByteBuffer.allocate((int)this.length);
		}

		int pos = block.position();
		this.data.position(offset);
		this.data.put(block);
		block.position(pos);

		if (block.remaining() + offset == this.length) {
			this.data.rewind();
			logger.trace("Recording {}...", this);
			this.bucket.write(this.data, this.offset);
			this.data = null;
		}
	}

	public String toString() {
		return String.format("piece#%4d%s",
			this.index,
			this.isValid() ? "+" : "-");
	}
	public int compareTo(Piece other) {
		if (this.seen != other.seen) {
			return this.seen < other.seen ? -1 : 1;
		}
		return this.index == other.index ? 0 :
			(this.index < other.index ? -1 : 1);
	}

	public static class CallableHasher implements Callable<Piece> {

		private final Piece piece;

		public CallableHasher(Piece piece) {
			this.piece = piece;
		}

		@Override
		public Piece call() throws IOException {
			this.piece.validate();
			return this.piece;
		}
	}
}
