
package pl.uksw.edu.javatorrent.common.protocol.http;

import pl.uksw.edu.javatorrent.bcodec.BDecoder;
import pl.uksw.edu.javatorrent.bcodec.BEValue;
import pl.uksw.edu.javatorrent.bcodec.BEncoder;
import pl.uksw.edu.javatorrent.bcodec.InvalidBEncodingException;
import pl.uksw.edu.javatorrent.common.Peer;
import pl.uksw.edu.javatorrent.common.Torrent;
import pl.uksw.edu.javatorrent.common.protocol.TrackerMessage.AnnounceResponseMessage;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class HTTPAnnounceResponseMessage extends HTTPTrackerMessage
	implements AnnounceResponseMessage {

	private final int interval;
	private final int complete;
	private final int incomplete;
	private final List<Peer> peers;

	private HTTPAnnounceResponseMessage(ByteBuffer data,
		int interval, int complete, int incomplete, List<Peer> peers) {
		super(Type.ANNOUNCE_RESPONSE, data);
		this.interval = interval;
		this.complete = complete;
		this.incomplete = incomplete;
		this.peers = peers;
	}

	@Override
	public int getInterval() {
		return this.interval;
	}

	@Override
	public int getComplete() {
		return this.complete;
	}

	@Override
	public int getIncomplete() {
		return this.incomplete;
	}

	@Override
	public List<Peer> getPeers() {
		return this.peers;
	}

	public static HTTPAnnounceResponseMessage parse(ByteBuffer data)
		throws IOException, MessageValidationException {
		BEValue decoded = BDecoder.bdecode(data);
		if (decoded == null) {
			throw new MessageValidationException(
				"Could not decode tracker message (not B-encoded?)!");
		}

		Map<String, BEValue> params = decoded.getMap();

		if (params.get("interval") == null) {
			throw new MessageValidationException(
				"Tracker message missing mandatory field 'interval'!");
		}

		try {
			List<Peer> peers;

			try {

				peers = toPeerList(params.get("peers").getBytes());
			} catch (InvalidBEncodingException ibee) {
				peers = toPeerList(params.get("peers").getList());
			}

			return new HTTPAnnounceResponseMessage(data,
				params.get("interval").getInt(),
				params.get("complete") != null ? params.get("complete").getInt() : 0,
				params.get("incomplete") != null ? params.get("incomplete").getInt() : 0,
				peers);
		} catch (InvalidBEncodingException ibee) {
			throw new MessageValidationException("Invalid response " +
				"from tracker!", ibee);
		} catch (UnknownHostException uhe) {
			throw new MessageValidationException("Invalid peer " +
				"in tracker response!", uhe);
		}
	}
	private static List<Peer> toPeerList(List<BEValue> peers)
		throws InvalidBEncodingException {
		List<Peer> result = new LinkedList<Peer>();

		for (BEValue peer : peers) {
			Map<String, BEValue> peerInfo = peer.getMap();
			result.add(new Peer(
				peerInfo.get("ip").getString(Torrent.BYTE_ENCODING),
				peerInfo.get("port").getInt()));
		}

		return result;
	}
	private static List<Peer> toPeerList(byte[] data)
		throws InvalidBEncodingException, UnknownHostException {
		if (data.length % 6 != 0) {
			throw new InvalidBEncodingException("Invalid peers " +
				"binary information string!");
		}

		List<Peer> result = new LinkedList<Peer>();
		ByteBuffer peers = ByteBuffer.wrap(data);

		for (int i=0; i < data.length / 6 ; i++) {
			byte[] ipBytes = new byte[4];
			peers.get(ipBytes);
			InetAddress ip = InetAddress.getByAddress(ipBytes);
			int port =
				(0xFF & (int)peers.get()) << 8 |
				(0xFF & (int)peers.get());
			result.add(new Peer(new InetSocketAddress(ip, port)));
		}

		return result;
	}
	public static HTTPAnnounceResponseMessage craft(int interval,
		int minInterval, String trackerId, int complete, int incomplete,
		List<Peer> peers) throws IOException, UnsupportedEncodingException {
		Map<String, BEValue> response = new HashMap<String, BEValue>();
		response.put("interval", new BEValue(interval));
		response.put("complete", new BEValue(complete));
		response.put("incomplete", new BEValue(incomplete));

		ByteBuffer data = ByteBuffer.allocate(peers.size() * 6);
		for (Peer peer : peers) {
			byte[] ip = peer.getRawIp();
			if (ip == null || ip.length != 4) {
				continue;
			}
			data.put(ip);
			data.putShort((short)peer.getPort());
		}
		response.put("peers", new BEValue(data.array()));

		return new HTTPAnnounceResponseMessage(
			BEncoder.bencode(response),
			interval, complete, incomplete, peers);
	}
}
