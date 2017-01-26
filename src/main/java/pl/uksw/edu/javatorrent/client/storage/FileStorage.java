
package pl.uksw.edu.javatorrent.client.storage;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

public class FileStorage implements TorrentByteStorage {

	private static final Logger logger =
		LoggerFactory.getLogger(FileStorage.class);

	private final File target;
	private final File partial;
	private final long offset;
	private final long size;

	private RandomAccessFile raf;
	private FileChannel channel;
	private File current;

	public FileStorage(File file, long size) throws IOException {
		this(file, 0, size);
	}

	public FileStorage(File file, long offset, long size)
		throws IOException {
		this.target = file;
		this.offset = offset;
		this.size = size;

		this.partial = new File(this.target.getAbsolutePath() +
			TorrentByteStorage.PARTIAL_FILE_NAME_SUFFIX);

		if (this.partial.exists()) {
			logger.debug("Partial download found at {}. Continuing...",
				this.partial.getAbsolutePath());
			this.current = this.partial;
		} else if (!this.target.exists()) {
			logger.debug("Downloading new file to {}...",
				this.partial.getAbsolutePath());
			this.current = this.partial;
		} else {
			logger.debug("Using existing file {}.",
				this.target.getAbsolutePath());
			this.current = this.target;
		}

		this.raf = new RandomAccessFile(this.current, "rw");

		if (file.length() != this.size) {
			this.raf.setLength(this.size);
		}

		this.channel = raf.getChannel();
		logger.info("Initialized byte storage file at {} " +
			"({}+{} byte(s)).",
			new Object[] {
				this.current.getAbsolutePath(),
				this.offset,
				this.size,
			});
	}

	protected long offset() {
		return this.offset;
	}

	@Override
	public long size() {
		return this.size;
	}

	@Override
	public int read(ByteBuffer buffer, long offset) throws IOException {
		int requested = buffer.remaining();

		if (offset + requested > this.size) {
			throw new IllegalArgumentException("Invalid storage read request!");
		}

		int bytes = this.channel.read(buffer, offset);
		if (bytes < requested) {
			throw new IOException("Storage underrun!");
		}

		return bytes;
	}

	@Override
	public int write(ByteBuffer buffer, long offset) throws IOException {
		int requested = buffer.remaining();

		if (offset + requested > this.size) {
			throw new IllegalArgumentException("Invalid storage write request!");
		}

		return this.channel.write(buffer, offset);
	}

	@Override
	public synchronized void close() throws IOException {
		logger.debug("Closing file channel to " + this.current.getName() + "...");
		if (this.channel.isOpen()) {
			this.channel.force(true);
		}
		this.raf.close();
	}
	@Override
	public synchronized void finish() throws IOException {
		logger.debug("Closing file channel to " + this.current.getName() +
			" (download complete).");
		if (this.channel.isOpen()) {
			this.channel.force(true);
		}

		// Nothing more to do if we're already on the target file.
		if (this.isFinished()) {
			return;
		}

		this.raf.close();
		FileUtils.deleteQuietly(this.target);
		FileUtils.moveFile(this.current, this.target);

		logger.debug("Re-opening torrent byte storage at {}.",
				this.target.getAbsolutePath());

		this.raf = new RandomAccessFile(this.target, "rw");
		this.raf.setLength(this.size);
		this.channel = this.raf.getChannel();
		this.current = this.target;

		FileUtils.deleteQuietly(this.partial);
		logger.info("Moved torrent data from {} to {}.",
			this.partial.getName(),
			this.target.getName());
	}

	@Override
	public boolean isFinished() {
		return this.current.equals(this.target);
	}
}
