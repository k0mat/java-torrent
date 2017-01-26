
package pl.uksw.edu.javatorrent.tracker;

import pl.uksw.edu.javatorrent.bcodec.BEValue;
import pl.uksw.edu.javatorrent.common.Peer;
import pl.uksw.edu.javatorrent.common.Torrent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;


public class TrackedPeer extends Peer {

	private static final Logger logger =
		LoggerFactory.getLogger(TrackedPeer.class);

	private static final int FRESH_TIME_SECONDS = 30;

	private long uploaded;
	private long downloaded;
	private long left;
	private Torrent torrent;
	public enum PeerState {
		UNKNOWN,
		STARTED,
		COMPLETED,
		STOPPED;
	};

	private PeerState state;
	private Date lastAnnounce;
	public TrackedPeer(Torrent torrent, String ip, int port,
			ByteBuffer peerId) {
		super(ip, port, peerId);
		this.torrent = torrent;

		// Instantiated peers start in the UNKNOWN state.
		this.state = PeerState.UNKNOWN;
		this.lastAnnounce = null;

		this.uploaded = 0;
		this.downloaded = 0;
		this.left = 0;
	}
	public void update(PeerState state, long uploaded, long downloaded,
			long left) {
		if (PeerState.STARTED.equals(state) && left == 0) {
			state = PeerState.COMPLETED;
		}

		if (!state.equals(this.state)) {
			logger.info("Peer {} {} download of {}.",
				new Object[] {
					this,
					state.name().toLowerCase(),
					this.torrent,
				});
		}

		this.state = state;
		this.lastAnnounce = new Date();
		this.uploaded = uploaded;
		this.downloaded = downloaded;
		this.left = left;
	}
	public boolean isCompleted() {
		return PeerState.COMPLETED.equals(this.state);
	}

	public long getUploaded() {
		return this.uploaded;
	}

	public long getDownloaded() {
		return this.downloaded;
	}

	public long getLeft() {
		return this.left;
	}

	public boolean isFresh() {
		return (this.lastAnnounce != null &&
				(this.lastAnnounce.getTime() + (FRESH_TIME_SECONDS * 1000) >
				 new Date().getTime()));
	}

	public BEValue toBEValue() throws UnsupportedEncodingException {
		Map<String, BEValue> peer = new HashMap<String, BEValue>();
		if (this.hasPeerId()) {
			peer.put("peer id", new BEValue(this.getPeerId().array()));
		}
		peer.put("ip", new BEValue(this.getIp(), Torrent.BYTE_ENCODING));
		peer.put("port", new BEValue(this.getPort()));
		return new BEValue(peer);
	}
}
