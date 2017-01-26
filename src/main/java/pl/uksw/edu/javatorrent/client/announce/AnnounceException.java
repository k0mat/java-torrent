
package pl.uksw.edu.javatorrent.client.announce;

public class AnnounceException extends Exception {

	private static final long serialVersionUID = -1;

	public AnnounceException(String message) {
		super(message);
	}

	public AnnounceException(Throwable cause) {
		super(cause);
	}

	public AnnounceException(String message, Throwable cause) {
		super(message, cause);
	}
}
