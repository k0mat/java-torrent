
package pl.uksw.edu.javatorrent.common.protocol.udp;

import pl.uksw.edu.javatorrent.common.Torrent;
import pl.uksw.edu.javatorrent.common.protocol.TrackerMessage;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;

public class UDPTrackerErrorMessage
	extends UDPTrackerMessage.UDPTrackerResponseMessage
	implements TrackerMessage.ErrorMessage {

	private static final int UDP_TRACKER_ERROR_MIN_MESSAGE_SIZE = 8;

	private final int actionId = Type.ERROR.getId();
	private final int transactionId;
	private final String reason;

	private UDPTrackerErrorMessage(ByteBuffer data, int transactionId,
		String reason) {
		super(Type.ERROR, data);
		this.transactionId = transactionId;
		this.reason = reason;
	}

	@Override
	public int getActionId() {
		return this.actionId;
	}

	@Override
	public int getTransactionId() {
		return this.transactionId;
	}

	@Override
	public String getReason() {
		return this.reason;
	}

	public static UDPTrackerErrorMessage parse(ByteBuffer data)
		throws MessageValidationException {
		if (data.remaining() < UDP_TRACKER_ERROR_MIN_MESSAGE_SIZE) {
			throw new MessageValidationException(
				"Invalid tracker error message size!");
		}

		if (data.getInt() != Type.ERROR.getId()) {
			throw new MessageValidationException(
				"Invalid action code for tracker error!");
		}

		int transactionId = data.getInt();
		byte[] reasonBytes = new byte[data.remaining()];
		data.get(reasonBytes);

		try {
			return new UDPTrackerErrorMessage(data,
				transactionId,
				new String(reasonBytes, Torrent.BYTE_ENCODING)
			);
		} catch (UnsupportedEncodingException uee) {
			throw new MessageValidationException(
				"Could not decode error message!", uee);
		}
	}

	public static UDPTrackerErrorMessage craft(int transactionId,
		String reason) throws UnsupportedEncodingException {
		byte[] reasonBytes = reason.getBytes(Torrent.BYTE_ENCODING);
		ByteBuffer data = ByteBuffer
			.allocate(UDP_TRACKER_ERROR_MIN_MESSAGE_SIZE +
				reasonBytes.length);
		data.putInt(Type.ERROR.getId());
		data.putInt(transactionId);
		data.put(reasonBytes);
		return new UDPTrackerErrorMessage(data,
			transactionId,
			reason);
	}
}
