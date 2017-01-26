
package pl.uksw.edu.javatorrent.common.protocol.udp;

import pl.uksw.edu.javatorrent.common.protocol.TrackerMessage;

import java.nio.ByteBuffer;

public abstract class UDPTrackerMessage extends TrackerMessage {

	private UDPTrackerMessage(Type type, ByteBuffer data) {
		super(type, data);
	}

	public abstract int getActionId();
	public abstract int getTransactionId();

	public static abstract class UDPTrackerRequestMessage
		extends UDPTrackerMessage {

		private static final int UDP_MIN_REQUEST_PACKET_SIZE = 16;

		protected UDPTrackerRequestMessage(Type type, ByteBuffer data) {
			super(type, data);
		}

		public static UDPTrackerRequestMessage parse(ByteBuffer data)
			throws MessageValidationException {
			if (data.remaining() < UDP_MIN_REQUEST_PACKET_SIZE) {
				throw new MessageValidationException("Invalid packet size!");
			}
			data.mark();
			data.getLong();
			int action = data.getInt();
			data.reset();

			if (action == Type.CONNECT_REQUEST.getId()) {
				return UDPConnectRequestMessage.parse(data);
			} else if (action == Type.ANNOUNCE_REQUEST.getId()) {
				return UDPAnnounceRequestMessage.parse(data);
			}

			throw new MessageValidationException("Unknown UDP tracker " +
				"request message!");
		}
	};

	public static abstract class UDPTrackerResponseMessage
		extends UDPTrackerMessage {

		private static final int UDP_MIN_RESPONSE_PACKET_SIZE = 8;

		protected UDPTrackerResponseMessage(Type type, ByteBuffer data) {
			super(type, data);
		}

		public static UDPTrackerResponseMessage parse(ByteBuffer data)
			throws MessageValidationException {
			if (data.remaining() < UDP_MIN_RESPONSE_PACKET_SIZE) {
				throw new MessageValidationException("Invalid packet size!");
			}

			data.mark();
			int action = data.getInt();
			data.reset();

			if (action == Type.CONNECT_RESPONSE.getId()) {
				return UDPConnectResponseMessage.parse(data);
			} else if (action == Type.ANNOUNCE_RESPONSE.getId()) {
				return UDPAnnounceResponseMessage.parse(data);
			} else if (action == Type.ERROR.getId()) {
				return UDPTrackerErrorMessage.parse(data);
			}

			throw new MessageValidationException("Unknown UDP tracker " +
				"response message!");
		}
	};
}
