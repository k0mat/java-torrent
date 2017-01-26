
package pl.uksw.edu.javatorrent.common.protocol.http;

import pl.uksw.edu.javatorrent.bcodec.BDecoder;
import pl.uksw.edu.javatorrent.bcodec.BEValue;
import pl.uksw.edu.javatorrent.bcodec.BEncoder;
import pl.uksw.edu.javatorrent.bcodec.InvalidBEncodingException;
import pl.uksw.edu.javatorrent.common.Torrent;
import pl.uksw.edu.javatorrent.common.protocol.TrackerMessage.ErrorMessage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

public class HTTPTrackerErrorMessage extends HTTPTrackerMessage
	implements ErrorMessage {

	private final String reason;

	private HTTPTrackerErrorMessage(ByteBuffer data, String reason) {
		super(Type.ERROR, data);
		this.reason = reason;
	}

	@Override
	public String getReason() {
		return this.reason;
	}

	public static HTTPTrackerErrorMessage parse(ByteBuffer data)
		throws IOException, MessageValidationException {
		BEValue decoded = BDecoder.bdecode(data);
		if (decoded == null) {
			throw new MessageValidationException(
				"Could not decode tracker message (not B-encoded?)!");
		}

		Map<String, BEValue> params = decoded.getMap();

		try {
			return new HTTPTrackerErrorMessage(
				data,
				params.get("failure reason")
					.getString(Torrent.BYTE_ENCODING));
		} catch (InvalidBEncodingException ibee) {
			throw new MessageValidationException("Invalid tracker error " +
				"message!", ibee);
		}
	}

	public static HTTPTrackerErrorMessage craft(
		ErrorMessage.FailureReason reason) throws IOException,
		   MessageValidationException {
		return HTTPTrackerErrorMessage.craft(reason.getMessage());
	}

	public static HTTPTrackerErrorMessage craft(String reason)
		throws IOException, MessageValidationException {
		Map<String, BEValue> params = new HashMap<String, BEValue>();
		params.put("failure reason",
			new BEValue(reason, Torrent.BYTE_ENCODING));
		return new HTTPTrackerErrorMessage(
			BEncoder.bencode(params),
			reason);
	}
}
