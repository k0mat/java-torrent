
package pl.uksw.edu.javatorrent.common.protocol;

import pl.uksw.edu.javatorrent.common.Peer;

import java.nio.ByteBuffer;
import java.util.List;

public abstract class TrackerMessage {

	/**
	 * Message type.
	 */
	public enum Type {
		UNKNOWN(-1),
		CONNECT_REQUEST(0),
		CONNECT_RESPONSE(0),
		ANNOUNCE_REQUEST(1),
		ANNOUNCE_RESPONSE(1),
		SCRAPE_REQUEST(2),
		SCRAPE_RESPONSE(2),
		ERROR(3);

		private final int id;

		Type(int id) {
			this.id = id;
		}

		public int getId() {
			return this.id;
		}
	};

	private final Type type;
	private final ByteBuffer data;
	protected TrackerMessage(Type type, ByteBuffer data) {
		this.type = type;
		this.data = data;
		if (this.data != null) {
			this.data.rewind();
		}
	}


	public Type getType() {
		return this.type;
	}

	public ByteBuffer getData() {
		return this.data;
	}

	public static class MessageValidationException extends Exception {

		static final long serialVersionUID = -1;

		public MessageValidationException(String s) {
			super(s);
		}

		public MessageValidationException(String s, Throwable cause) {
			super(s, cause);
		}

	}

	public interface ConnectionRequestMessage {

	};

	public interface ConnectionResponseMessage {

	};

	public interface AnnounceRequestMessage {

		public static final int DEFAULT_NUM_WANT = 50;

		public enum RequestEvent {
			NONE(0),
			COMPLETED(1),
			STARTED(2),
			STOPPED(3);

			private final int id;
			RequestEvent(int id) {
				this.id = id;
			}

			public String getEventName() {
				return this.name().toLowerCase();
			}

			public int getId() {
				return this.id;
			}

			public static RequestEvent getByName(String name) {
				for (RequestEvent type : RequestEvent.values()) {
					if (type.name().equalsIgnoreCase(name)) {
						return type;
					}
				}
				return null;
			}

			public static RequestEvent getById(int id) {
				for (RequestEvent type : RequestEvent.values()) {
					if (type.getId() == id) {
						return type;
					}
				}
				return null;
			}
		};

		public byte[] getInfoHash();
		public String getHexInfoHash();
		public byte[] getPeerId();
		public String getHexPeerId();
		public int getPort();
		public long getUploaded();
		public long getDownloaded();
		public long getLeft();
		public boolean getCompact();
		public boolean getNoPeerIds();
		public RequestEvent getEvent();

		public String getIp();
		public int getNumWant();
	};

	public interface AnnounceResponseMessage {

		public int getInterval();
		public int getComplete();
		public int getIncomplete();
		public List<Peer> getPeers();
	};
	public interface ErrorMessage {
		public enum FailureReason {
			UNKNOWN_TORRENT("The requested torrent does not exist on this tracker"),
			MISSING_HASH("Missing info hash"),
			MISSING_PEER_ID("Missing peer ID"),
			MISSING_PORT("Missing port"),
			INVALID_EVENT("Unexpected event for peer state"),
			NOT_IMPLEMENTED("Feature not implemented");

			private String message;

			FailureReason(String message) {
				this.message = message;
			}

			public String getMessage() {
				return this.message;
			}
		};

		public String getReason();
	};
}
