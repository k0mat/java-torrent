
package pl.uksw.edu.javatorrent.common.protocol.http;

import pl.uksw.edu.javatorrent.bcodec.BDecoder;
import pl.uksw.edu.javatorrent.bcodec.BEValue;
import pl.uksw.edu.javatorrent.common.protocol.TrackerMessage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;

public abstract class HTTPTrackerMessage extends TrackerMessage {

	protected HTTPTrackerMessage(Type type, ByteBuffer data) {
		super(type, data);
	}

	public static HTTPTrackerMessage parse(ByteBuffer data)
		throws IOException, MessageValidationException {
		BEValue decoded = BDecoder.bdecode(data);
		if (decoded == null) {
			throw new MessageValidationException(
				"Could not decode tracker message (not B-encoded?)!");
		}

		Map<String, BEValue> params = decoded.getMap();

		if (params.containsKey("info_hash")) {
			return HTTPAnnounceRequestMessage.parse(data);
		} else if (params.containsKey("peers")) {
			return HTTPAnnounceResponseMessage.parse(data);
		} else if (params.containsKey("failure reason")) {
			return HTTPTrackerErrorMessage.parse(data);
		}

		throw new MessageValidationException("Unknown HTTP tracker message!");
	}
}
