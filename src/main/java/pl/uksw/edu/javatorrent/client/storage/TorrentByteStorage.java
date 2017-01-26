
package pl.uksw.edu.javatorrent.client.storage;

import java.io.IOException;
import java.nio.ByteBuffer;

public interface TorrentByteStorage {
	public static final String PARTIAL_FILE_NAME_SUFFIX = ".part";
	public long size();
	public int read(ByteBuffer buffer, long offset) throws IOException;
	public int write(ByteBuffer block, long offset) throws IOException;
	public void close() throws IOException;
	public void finish() throws IOException;
	public boolean isFinished();
}
