
package pl.uksw.edu.javatorrent.client.announce;

import pl.uksw.edu.javatorrent.client.SharedTorrent;
import pl.uksw.edu.javatorrent.common.Peer;
import pl.uksw.edu.javatorrent.common.protocol.TrackerMessage;
import pl.uksw.edu.javatorrent.common.protocol.TrackerMessage.*;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public abstract class TrackerClient {

	private final Set<AnnounceResponseListener> listeners;

	protected final SharedTorrent torrent;
	protected final Peer peer;
	protected final URI tracker;

	public TrackerClient(SharedTorrent torrent, Peer peer, URI tracker) {
		this.listeners = new HashSet<AnnounceResponseListener>();
		this.torrent = torrent;
		this.peer = peer;
		this.tracker = tracker;
	}


	public void register(AnnounceResponseListener listener) {
		this.listeners.add(listener);
	}


	public URI getTrackerURI() {
		return this.tracker;
	}

	public abstract void announce(AnnounceRequestMessage.RequestEvent event,
		boolean inhibitEvent) throws AnnounceException;


	protected void close() {
		// Do nothing by default, but can be overloaded.
	}
	protected String formatAnnounceEvent(
		AnnounceRequestMessage.RequestEvent event) {
		return AnnounceRequestMessage.RequestEvent.NONE.equals(event)
			? ""
			: String.format(" %s", event.name());
	}

	protected void handleTrackerAnnounceResponse(TrackerMessage message,
		boolean inhibitEvents) throws AnnounceException {
		if (message instanceof ErrorMessage) {
			ErrorMessage error = (ErrorMessage)message;
			throw new AnnounceException(error.getReason());
		}

		if (! (message instanceof AnnounceResponseMessage)) {
			throw new AnnounceException("Unexpected tracker message type " +
				message.getType().name() + "!");
		}

		if (inhibitEvents) {
			return;
		}

		AnnounceResponseMessage response =
			(AnnounceResponseMessage)message;
		this.fireAnnounceResponseEvent(
			response.getComplete(),
			response.getIncomplete(),
			response.getInterval());
		this.fireDiscoveredPeersEvent(
			response.getPeers());
	}

	protected void fireAnnounceResponseEvent(int complete, int incomplete,
		int interval) {
		for (AnnounceResponseListener listener : this.listeners) {
			listener.handleAnnounceResponse(interval, complete, incomplete);
		}
	}
	protected void fireDiscoveredPeersEvent(List<Peer> peers) {
		for (AnnounceResponseListener listener : this.listeners) {
			listener.handleDiscoveredPeers(peers);
		}
	}
}
