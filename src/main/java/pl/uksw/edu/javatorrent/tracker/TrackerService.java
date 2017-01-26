
package pl.uksw.edu.javatorrent.tracker;

import pl.uksw.edu.javatorrent.bcodec.BEValue;
import pl.uksw.edu.javatorrent.bcodec.BEncoder;
import pl.uksw.edu.javatorrent.common.protocol.TrackerMessage.*;
import pl.uksw.edu.javatorrent.common.protocol.http.*;
import org.apache.commons.io.IOUtils;
import org.simpleframework.http.Request;
import org.simpleframework.http.Response;
import org.simpleframework.http.Status;
import org.simpleframework.http.core.Container;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

public class TrackerService implements Container {

	private static final Logger logger =
		LoggerFactory.getLogger(TrackerService.class);
	private static final String[] NUMERIC_REQUEST_FIELDS =
		new String[] {
			"port", "uploaded", "downloaded", "left",
			"compact", "no_peer_id", "numwant"
		};

	private final String version;
	private final ConcurrentMap<String, TrackedTorrent> torrents;
	TrackerService(String version,
			ConcurrentMap<String, TrackedTorrent> torrents) {
		this.version = version;
		this.torrents = torrents;
	}
	public void handle(Request request, Response response) {
		// Reject non-announce requests
		if (!Tracker.ANNOUNCE_URL.equals(request.getPath().toString())) {
			response.setCode(404);
			response.setText("Not Found");
			return;
		}

		OutputStream body = null;
		try {
			body = response.getOutputStream();
			this.process(request, response, body);
			body.flush();
		} catch (IOException ioe) {
			logger.warn("Error while writing response: {}!", ioe.getMessage());
		} finally {
			IOUtils.closeQuietly(body);
		}
	}
	private void process(Request request, Response response,
			OutputStream body) throws IOException {
		response.set("Content-Type", "text/plain");
		response.set("Server", this.version);
		response.setDate("Date", System.currentTimeMillis());
		HTTPAnnounceRequestMessage announceRequest = null;
		try {
			announceRequest = this.parseQuery(request);
		} catch (MessageValidationException mve) {
			this.serveError(response, body, Status.BAD_REQUEST,
				mve.getMessage());
			return;
		}
		TrackedTorrent torrent = this.torrents.get(
			announceRequest.getHexInfoHash());
		if (torrent == null) {
			logger.warn("Requested torrent hash was: {}",
				announceRequest.getHexInfoHash());
			this.serveError(response, body, Status.BAD_REQUEST,
				ErrorMessage.FailureReason.UNKNOWN_TORRENT);
			return;
		}

		AnnounceRequestMessage.RequestEvent event = announceRequest.getEvent();
		String peerId = announceRequest.getHexPeerId();
		if ((event == null ||
				AnnounceRequestMessage.RequestEvent.NONE.equals(event)) &&
			torrent.getPeer(peerId) == null) {
			event = AnnounceRequestMessage.RequestEvent.STARTED;
		}
		if (event != null && torrent.getPeer(peerId) == null &&
			!AnnounceRequestMessage.RequestEvent.STARTED.equals(event)) {
			this.serveError(response, body, Status.BAD_REQUEST,
				ErrorMessage.FailureReason.INVALID_EVENT);
			return;
		}
		TrackedPeer peer = null;
		try {
			peer = torrent.update(event,
				ByteBuffer.wrap(announceRequest.getPeerId()),
				announceRequest.getHexPeerId(),
				announceRequest.getIp(),
				announceRequest.getPort(),
				announceRequest.getUploaded(),
				announceRequest.getDownloaded(),
				announceRequest.getLeft());
		} catch (IllegalArgumentException iae) {
			this.serveError(response, body, Status.BAD_REQUEST,
				ErrorMessage.FailureReason.INVALID_EVENT);
			return;
		}
		HTTPAnnounceResponseMessage announceResponse = null;
		try {
			announceResponse = HTTPAnnounceResponseMessage.craft(
				torrent.getAnnounceInterval(),
				TrackedTorrent.MIN_ANNOUNCE_INTERVAL_SECONDS,
				this.version,
				torrent.seeders(),
				torrent.leechers(),
				torrent.getSomePeers(peer));
			WritableByteChannel channel = Channels.newChannel(body);
			channel.write(announceResponse.getData());
		} catch (Exception e) {
			this.serveError(response, body, Status.INTERNAL_SERVER_ERROR,
				e.getMessage());
		}
	}
	private HTTPAnnounceRequestMessage parseQuery(Request request)
		throws IOException, MessageValidationException {
		Map<String, BEValue> params = new HashMap<String, BEValue>();

		try {
			String uri = request.getAddress().toString();
			for (String pair : uri.split("[?]")[1].split("&")) {
				String[] keyval = pair.split("[=]", 2);
				if (keyval.length == 1) {
					this.recordParam(params, keyval[0], null);
				} else {
					this.recordParam(params, keyval[0], keyval[1]);
				}
			}
		} catch (ArrayIndexOutOfBoundsException e) {
			params.clear();
		}
		if (params.get("ip") == null) {
			params.put("ip", new BEValue(
				request.getClientAddress().getAddress().getHostAddress(),
				TrackedTorrent.BYTE_ENCODING));
		}


		return HTTPAnnounceRequestMessage.parse(BEncoder.bencode(params));
	}

	private void recordParam(Map<String, BEValue> params, String key,
		String value) {
		try {
			value = URLDecoder.decode(value, TrackedTorrent.BYTE_ENCODING);

			for (String f : NUMERIC_REQUEST_FIELDS) {
				if (f.equals(key)) {
					params.put(key, new BEValue(Long.valueOf(value)));
					return;
				}
			}

			params.put(key, new BEValue(value, TrackedTorrent.BYTE_ENCODING));
		} catch (UnsupportedEncodingException uee) {
			return;
		}
	}

	private void serveError(Response response, OutputStream body,
		Status status, HTTPTrackerErrorMessage error) throws IOException {
		response.setCode(status.getCode());
		response.setText(status.getDescription());
		logger.warn("Could not process announce request ({}) !",
			error.getReason());

		WritableByteChannel channel = Channels.newChannel(body);
		channel.write(error.getData());
	}
	private void serveError(Response response, OutputStream body,
		Status status, String error) throws IOException {
		try {
			this.serveError(response, body, status,
				HTTPTrackerErrorMessage.craft(error));
		} catch (MessageValidationException mve) {
			logger.warn("Could not craft tracker error message!", mve);
		}
	}
	private void serveError(Response response, OutputStream body,
		Status status, ErrorMessage.FailureReason reason) throws IOException {
		this.serveError(response, body, status, reason.getMessage());
	}
}
