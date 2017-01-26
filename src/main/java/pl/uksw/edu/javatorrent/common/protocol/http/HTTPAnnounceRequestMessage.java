
package pl.uksw.edu.javatorrent.common.protocol.http;

import pl.uksw.edu.javatorrent.bcodec.BDecoder;
import pl.uksw.edu.javatorrent.bcodec.BEValue;
import pl.uksw.edu.javatorrent.bcodec.BEncoder;
import pl.uksw.edu.javatorrent.bcodec.InvalidBEncodingException;
import pl.uksw.edu.javatorrent.common.Peer;
import pl.uksw.edu.javatorrent.common.Torrent;
import pl.uksw.edu.javatorrent.common.Utils;
import pl.uksw.edu.javatorrent.common.protocol.TrackerMessage.AnnounceRequestMessage;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

public class HTTPAnnounceRequestMessage extends HTTPTrackerMessage
	implements AnnounceRequestMessage {

	private final byte[] infoHash;
	private final Peer peer;
	private final long uploaded;
	private final long downloaded;
	private final long left;
	private final boolean compact;
	private final boolean noPeerId;
	private final RequestEvent event;
	private final int numWant;

	private HTTPAnnounceRequestMessage(ByteBuffer data,
		byte[] infoHash, Peer peer, long uploaded, long downloaded,
		long left, boolean compact, boolean noPeerId, RequestEvent event,
		int numWant) {
		super(Type.ANNOUNCE_REQUEST, data);
		this.infoHash = infoHash;
		this.peer = peer;
		this.downloaded = downloaded;
		this.uploaded = uploaded;
		this.left = left;
		this.compact = compact;
		this.noPeerId = noPeerId;
		this.event = event;
		this.numWant = numWant;
	}

	@Override
	public byte[] getInfoHash() {
		return this.infoHash;
	}

	@Override
	public String getHexInfoHash() {
		return Utils.bytesToHex(this.infoHash);
	}

	@Override
	public byte[] getPeerId() {
		return this.peer.getPeerId().array();
	}

	@Override
	public String getHexPeerId() {
		return this.peer.getHexPeerId();
	}

	@Override
	public int getPort() {
		return this.peer.getPort();
	}

	@Override
	public long getUploaded() {
		return this.uploaded;
	}

	@Override
	public long getDownloaded() {
		return this.downloaded;
	}

	@Override
	public long getLeft() {
		return this.left;
	}

	@Override
	public boolean getCompact() {
		return this.compact;
	}

	@Override
	public boolean getNoPeerIds() {
		return this.noPeerId;
	}

	@Override
	public RequestEvent getEvent() {
		return this.event;
	}

	@Override
	public String getIp() {
		return this.peer.getIp();
	}

	@Override
	public int getNumWant() {
		return this.numWant;
	}
	public URL buildAnnounceURL(URL trackerAnnounceURL)
		throws UnsupportedEncodingException, MalformedURLException {
		String base = trackerAnnounceURL.toString();
		StringBuilder url = new StringBuilder(base);
		url.append(base.contains("?") ? "&" : "?")
			.append("info_hash=")
			.append(URLEncoder.encode(
				new String(this.getInfoHash(), Torrent.BYTE_ENCODING),
				Torrent.BYTE_ENCODING))
			.append("&peer_id=")
			.append(URLEncoder.encode(
				new String(this.getPeerId(), Torrent.BYTE_ENCODING),
				Torrent.BYTE_ENCODING))
			.append("&port=").append(this.getPort())
			.append("&uploaded=").append(this.getUploaded())
			.append("&downloaded=").append(this.getDownloaded())
			.append("&left=").append(this.getLeft())
			.append("&compact=").append(this.getCompact() ? 1 : 0)
			.append("&no_peer_id=").append(this.getNoPeerIds() ? 1 : 0);

		if (this.getEvent() != null &&
			!RequestEvent.NONE.equals(this.getEvent())) {
			url.append("&event=").append(this.getEvent().getEventName());
		}

		if (this.getIp() != null) {
			url.append("&ip=").append(this.getIp());
		}

		return new URL(url.toString());
	}

	public static HTTPAnnounceRequestMessage parse(ByteBuffer data)
		throws IOException, MessageValidationException {
		BEValue decoded = BDecoder.bdecode(data);
		if (decoded == null) {
			throw new MessageValidationException(
				"Could not decode tracker message (not B-encoded?)!");
		}

		Map<String, BEValue> params = decoded.getMap();

		if (!params.containsKey("info_hash")) {
			throw new MessageValidationException(
				ErrorMessage.FailureReason.MISSING_HASH.getMessage());
		}

		if (!params.containsKey("peer_id")) {
			throw new MessageValidationException(
				ErrorMessage.FailureReason.MISSING_PEER_ID.getMessage());
		}

		if (!params.containsKey("port")) {
			throw new MessageValidationException(
				ErrorMessage.FailureReason.MISSING_PORT.getMessage());
		}

		try {
			byte[] infoHash = params.get("info_hash").getBytes();
			byte[] peerId = params.get("peer_id").getBytes();
			int port = params.get("port").getInt();
			long uploaded = 0;
			if (params.containsKey("uploaded")) {
				uploaded = params.get("uploaded").getLong();
			}

			long downloaded = 0;
			if (params.containsKey("downloaded")) {
				downloaded = params.get("downloaded").getLong();
			}
			long left = -1;
			if (params.containsKey("left")) {
				left = params.get("left").getLong();
			}

			boolean compact = false;
			if (params.containsKey("compact")) {
				compact = params.get("compact").getInt() == 1;
			}

			boolean noPeerId = false;
			if (params.containsKey("no_peer_id")) {
				noPeerId = params.get("no_peer_id").getInt() == 1;
			}

			int numWant = AnnounceRequestMessage.DEFAULT_NUM_WANT;
			if (params.containsKey("numwant")) {
				numWant = params.get("numwant").getInt();
			}

			String ip = null;
			if (params.containsKey("ip")) {
				ip = params.get("ip").getString(Torrent.BYTE_ENCODING);
			}

			RequestEvent event = RequestEvent.NONE;
			if (params.containsKey("event")) {
				event = RequestEvent.getByName(params.get("event")
					.getString(Torrent.BYTE_ENCODING));
			}

			return new HTTPAnnounceRequestMessage(data, infoHash,
				new Peer(ip, port, ByteBuffer.wrap(peerId)),
				uploaded, downloaded, left, compact, noPeerId,
				event, numWant);
		} catch (InvalidBEncodingException ibee) {
			throw new MessageValidationException(
				"Invalid HTTP tracker request!", ibee);
		}
	}

	public static HTTPAnnounceRequestMessage craft(byte[] infoHash,
		byte[] peerId, int port, long uploaded, long downloaded, long left,
		boolean compact, boolean noPeerId, RequestEvent event,
		String ip, int numWant)
		throws IOException, MessageValidationException,
			UnsupportedEncodingException {
		Map<String, BEValue> params = new HashMap<String, BEValue>();
		params.put("info_hash", new BEValue(infoHash));
		params.put("peer_id", new BEValue(peerId));
		params.put("port", new BEValue(port));
		params.put("uploaded", new BEValue(uploaded));
		params.put("downloaded", new BEValue(downloaded));
		params.put("left", new BEValue(left));
		params.put("compact", new BEValue(compact ? 1 : 0));
		params.put("no_peer_id", new BEValue(noPeerId ? 1 : 0));

		if (event != null) {
			params.put("event",
				new BEValue(event.getEventName(), Torrent.BYTE_ENCODING));
		}

		if (ip != null) {
			params.put("ip",
				new BEValue(ip, Torrent.BYTE_ENCODING));
		}

		if (numWant != AnnounceRequestMessage.DEFAULT_NUM_WANT) {
			params.put("numwant", new BEValue(numWant));
		}

		return new HTTPAnnounceRequestMessage(
			BEncoder.bencode(params),
			infoHash, new Peer(ip, port, ByteBuffer.wrap(peerId)),
			uploaded, downloaded, left, compact, noPeerId, event, numWant);
	}
}
