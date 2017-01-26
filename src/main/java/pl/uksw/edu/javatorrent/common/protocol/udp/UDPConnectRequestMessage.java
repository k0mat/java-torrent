
package pl.uksw.edu.javatorrent.common.protocol.udp;

import pl.uksw.edu.javatorrent.common.protocol.TrackerMessage;

import java.nio.ByteBuffer;

public class UDPConnectRequestMessage
	extends UDPTrackerMessage.UDPTrackerRequestMessage
	implements TrackerMessage.ConnectionRequestMessage {

	private static final int UDP_CONNECT_REQUEST_MESSAGE_SIZE = 16;
	private static final long UDP_CONNECT_REQUEST_MAGIC = 0x41727101980L;

	private final long connectionId = UDP_CONNECT_REQUEST_MAGIC;
	private final int actionId = Type.CONNECT_REQUEST.getId();
	private final int transactionId;

	private UDPConnectRequestMessage(ByteBuffer data, int transactionId) {
		super(Type.CONNECT_REQUEST, data);
		this.transactionId = transactionId;
	}

	public long getConnectionId() {
		return this.connectionId;
	}

	@Override
	public int getActionId() {
		return this.actionId;
	}

	@Override
	public int getTransactionId() {
		return this.transactionId;
	}

	public static UDPConnectRequestMessage parse(ByteBuffer data)
		throws MessageValidationException {
		if (data.remaining() != UDP_CONNECT_REQUEST_MESSAGE_SIZE) {
			throw new MessageValidationException(
				"Invalid connect request message size!");
		}

		if (data.getLong() != UDP_CONNECT_REQUEST_MAGIC) {
			throw new MessageValidationException(
				"Invalid connection ID in connection request!");
		}

		if (data.getInt() != Type.CONNECT_REQUEST.getId()) {
			throw new MessageValidationException(
				"Invalid action code for connection request!");
		}

		return new UDPConnectRequestMessage(data,
			data.getInt()
		);
	}

	public static UDPConnectRequestMessage craft(int transactionId) {
		ByteBuffer data = ByteBuffer
			.allocate(UDP_CONNECT_REQUEST_MESSAGE_SIZE);
		data.putLong(UDP_CONNECT_REQUEST_MAGIC);
		data.putInt(Type.CONNECT_REQUEST.getId());
		data.putInt(transactionId);
		return new UDPConnectRequestMessage(data,
			transactionId);
	}
}
