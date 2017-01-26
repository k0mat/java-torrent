
package pl.uksw.edu.javatorrent.client.announce;

import pl.uksw.edu.javatorrent.client.SharedTorrent;
import pl.uksw.edu.javatorrent.common.Peer;
import pl.uksw.edu.javatorrent.common.protocol.TrackerMessage.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.UnknownHostException;
import java.net.UnknownServiceException;
import java.util.*;

public class Announce implements Runnable {

	protected static final Logger logger =
		LoggerFactory.getLogger(Announce.class);

	private final Peer peer;


	private final List<List<TrackerClient>> clients;
	private final Set<TrackerClient> allClients;


	private Thread thread;
	private boolean stop;
	private boolean forceStop;

	private int interval;

	private int currentTier;
	private int currentClient;

	public Announce(SharedTorrent torrent, Peer peer) {
		this.peer = peer;
		this.clients = new ArrayList<List<TrackerClient>>();
		this.allClients = new HashSet<TrackerClient>();
		for (List<URI> tier : torrent.getAnnounceList()) {
			ArrayList<TrackerClient> tierClients = new ArrayList<TrackerClient>();
			for (URI tracker : tier) {
				try {
					TrackerClient client = this.createTrackerClient(torrent,
						peer, tracker);

					tierClients.add(client);
					this.allClients.add(client);
				} catch (Exception e) {
					logger.warn("Will not announce on {}: {}!",
						tracker,
						e.getMessage() != null
							? e.getMessage()
							: e.getClass().getSimpleName());
				}
			}
			Collections.shuffle(tierClients);
			clients.add(tierClients);
		}

		this.thread = null;
		this.currentTier = 0;
		this.currentClient = 0;

		logger.info("Initialized announce sub-system with {} trackers on {}.",
			new Object[] { torrent.getTrackerCount(), torrent });
	}
	public void register(AnnounceResponseListener listener) {
		for (TrackerClient client : this.allClients) {
			client.register(listener);
		}
	}

	public void start() {
		this.stop = false;
		this.forceStop = false;

		if (this.clients.size() > 0 && (this.thread == null || !this.thread.isAlive())) {
			this.thread = new Thread(this);
			this.thread.setName("bt-announce(" +
				this.peer.getShortHexPeerId() + ")");
			this.thread.start();
		}
	}
	public void setInterval(int interval) {
		if (interval <= 0) {
			this.stop(true);
			return;
		}

		if (this.interval == interval) {
			return;
		}

		logger.info("Setting announce interval to {}s per tracker request.",
			interval);
		this.interval = interval;
	}

	public void stop() {
		this.stop = true;

		if (this.thread != null && this.thread.isAlive()) {
			this.thread.interrupt();

			for (TrackerClient client : this.allClients) {
				client.close();
			}

			try {
				this.thread.join();
			} catch (InterruptedException ie) {
				// Ignore
			}
		}

		this.thread = null;
	}
	public void run() {
		logger.info("Starting announce loop...");
		this.interval = 5;

		AnnounceRequestMessage.RequestEvent event =
			AnnounceRequestMessage.RequestEvent.STARTED;

		while (!this.stop) {
			try {
				this.getCurrentTrackerClient().announce(event, false);
				this.promoteCurrentTrackerClient();
				event = AnnounceRequestMessage.RequestEvent.NONE;
			} catch (AnnounceException ae) {
				logger.warn(ae.getMessage());

				try {
					this.moveToNextTrackerClient();
				} catch (AnnounceException e) {
					logger.error("Unable to move to the next tracker client: {}", e.getMessage());
				}
			}

			try {
				Thread.sleep(this.interval * 1000);
			} catch (InterruptedException ie) {
			}
		}

		logger.info("Exited announce loop.");

		if (!this.forceStop) {
			event = AnnounceRequestMessage.RequestEvent.STOPPED;
			try {
				Thread.sleep(500);
			} catch (InterruptedException ie) {
			}

			try {
				this.getCurrentTrackerClient().announce(event, true);
			} catch (AnnounceException ae) {
				logger.warn(ae.getMessage());
			}
		}
	}

	private TrackerClient createTrackerClient(SharedTorrent torrent, Peer peer,
		URI tracker) throws UnknownHostException, UnknownServiceException {
		String scheme = tracker.getScheme();

		if ("http".equals(scheme) || "https".equals(scheme)) {
			return new HTTPTrackerClient(torrent, peer, tracker);
		} else if ("udp".equals(scheme)) {
			return new UDPTrackerClient(torrent, peer, tracker);
		}

		throw new UnknownServiceException(
			"Unsupported announce scheme: " + scheme + "!");
	}

	public TrackerClient getCurrentTrackerClient() throws AnnounceException {
		if ((this.currentTier >= this.clients.size()) ||
			(this.currentClient >= this.clients.get(this.currentTier).size())) {
			throw new AnnounceException("Current tier or client isn't available");
		}

		return this.clients
			.get(this.currentTier)
			.get(this.currentClient);
	}

	private void promoteCurrentTrackerClient() throws AnnounceException {
		logger.trace("Promoting current tracker client for {} " +
			"(tier {}, position {} -> 0).",
			new Object[] {
				this.getCurrentTrackerClient().getTrackerURI(),
				this.currentTier,
				this.currentClient
			});

		Collections.swap(this.clients.get(this.currentTier),
			this.currentClient, 0);
		this.currentClient = 0;
	}


	private void moveToNextTrackerClient() throws AnnounceException {
		int tier = this.currentTier;
		int client = this.currentClient + 1;

		if (client >= this.clients.get(tier).size()) {
			client = 0;

			tier++;

			if (tier >= this.clients.size()) {
				tier = 0;
			}
		}

		if (tier != this.currentTier ||
			client != this.currentClient) {
			this.currentTier = tier;
			this.currentClient = client;

			logger.debug("Switched to tracker client for {} " +
				"(tier {}, position {}).",
				new Object[] {
					this.getCurrentTrackerClient().getTrackerURI(),
					this.currentTier,
					this.currentClient
				});
		}
	}
	private void stop(boolean hard) {
		this.forceStop = hard;
		this.stop();
	}
}
