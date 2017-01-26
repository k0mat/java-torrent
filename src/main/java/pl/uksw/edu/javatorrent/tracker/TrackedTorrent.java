
package pl.uksw.edu.javatorrent.tracker;

import pl.uksw.edu.javatorrent.common.Peer;
import pl.uksw.edu.javatorrent.common.Torrent;
import pl.uksw.edu.javatorrent.common.protocol.TrackerMessage.AnnounceRequestMessage.RequestEvent;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class TrackedTorrent extends Torrent {

	private static final Logger logger =
		LoggerFactory.getLogger(TrackedTorrent.class);
	public static final int MIN_ANNOUNCE_INTERVAL_SECONDS = 5;
	private static final int DEFAULT_ANSWER_NUM_PEERS = 30;
	private static final int DEFAULT_ANNOUNCE_INTERVAL_SECONDS = 10;
	private int answerPeers;
	private int announceInterval;
	private ConcurrentMap<String, TrackedPeer> peers;
	public TrackedTorrent(byte[] torrent) throws IOException, NoSuchAlgorithmException {
		super(torrent, false);

		this.peers = new ConcurrentHashMap<String, TrackedPeer>();
		this.answerPeers = TrackedTorrent.DEFAULT_ANSWER_NUM_PEERS;
		this.announceInterval = TrackedTorrent.DEFAULT_ANNOUNCE_INTERVAL_SECONDS;
	}

	public TrackedTorrent(Torrent torrent) throws IOException, NoSuchAlgorithmException {
		this(torrent.getEncoded());
	}
	public Map<String, TrackedPeer> getPeers() {
		return this.peers;
	}
	public void addPeer(TrackedPeer peer) {
		this.peers.put(peer.getHexPeerId(), peer);
	}
	public TrackedPeer getPeer(String peerId) {
		return this.peers.get(peerId);
	}
	public TrackedPeer removePeer(String peerId) {
		return this.peers.remove(peerId);
	}
	public int seeders() {
		int count = 0;
		for (TrackedPeer peer : this.peers.values()) {
			if (peer.isCompleted()) {
				count++;
			}
		}
		return count;
	}
	public int leechers() {
		int count = 0;
		for (TrackedPeer peer : this.peers.values()) {
			if (!peer.isCompleted()) {
				count++;
			}
		}
		return count;
	}
	public void collectUnfreshPeers() {
		for (TrackedPeer peer : this.peers.values()) {
			if (!peer.isFresh()) {
				this.peers.remove(peer.getHexPeerId());
			}
		}
	}


	public int getAnnounceInterval() {
		return this.announceInterval;
	}

	public void setAnnounceInterval(int interval) {
		if (interval <= 0) {
			throw new IllegalArgumentException("Invalid announce interval");
		}

		this.announceInterval = interval;
	}

	public TrackedPeer update(RequestEvent event, ByteBuffer peerId,
		String hexPeerId, String ip, int port, long uploaded, long downloaded,
		long left) throws UnsupportedEncodingException {
		TrackedPeer peer;
		TrackedPeer.PeerState state = TrackedPeer.PeerState.UNKNOWN;

		if (RequestEvent.STARTED.equals(event)) {
			peer = new TrackedPeer(this, ip, port, peerId);
			state = TrackedPeer.PeerState.STARTED;
			this.addPeer(peer);
		} else if (RequestEvent.STOPPED.equals(event)) {
			peer = this.removePeer(hexPeerId);
			state = TrackedPeer.PeerState.STOPPED;
		} else if (RequestEvent.COMPLETED.equals(event)) {
			peer = this.getPeer(hexPeerId);
			state = TrackedPeer.PeerState.COMPLETED;
		} else if (RequestEvent.NONE.equals(event)) {
			peer = this.getPeer(hexPeerId);
			state = TrackedPeer.PeerState.STARTED;
		} else {
			throw new IllegalArgumentException("Unexpected announce event type!");
		}

		peer.update(state, uploaded, downloaded, left);
		return peer;
	}
	public List<Peer> getSomePeers(TrackedPeer peer) {
		List<Peer> peers = new LinkedList<Peer>();
		List<TrackedPeer> candidates =
			new LinkedList<TrackedPeer>(this.peers.values());
		Collections.shuffle(candidates);

		int count = 0;
		for (TrackedPeer candidate : candidates) {
			if (!candidate.isFresh() ||
				(candidate.looksLike(peer) && !candidate.equals(peer))) {
				logger.debug("Collecting stale peer {}...", candidate);
				this.peers.remove(candidate.getHexPeerId());
				continue;
			}
			if (peer.looksLike(candidate)) {
				continue;
			}

			if (!candidate.isFresh()) {
				logger.debug("Collecting stale peer {}...",
					candidate.getHexPeerId());
				this.peers.remove(candidate.getHexPeerId());
				continue;
			}

			if (count++ > this.answerPeers) {
				break;
			}

			peers.add(candidate);
		}

		return peers;
	}

	public static TrackedTorrent load(File torrent) throws IOException, NoSuchAlgorithmException {
		byte[] data = FileUtils.readFileToByteArray(torrent);
		return new TrackedTorrent(data);
	}
}
