package pl.uksw.edu.javatorrent.tracker;

import pl.uksw.edu.javatorrent.common.Torrent;
import org.simpleframework.transport.connect.Connection;
import org.simpleframework.transport.connect.SocketConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collection;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;


public class Tracker {

	private static final Logger logger =
		LoggerFactory.getLogger(Tracker.class);

	public static final String ANNOUNCE_URL = "/announce";

	public static final int DEFAULT_TRACKER_PORT = 6969;

	public static final String DEFAULT_VERSION_STRING =
		"BitTorrent Tracker (ttorrent)";

	private final Connection connection;
	private final InetSocketAddress address;
	private final ConcurrentMap<String, TrackedTorrent> torrents;

	private Thread tracker;
	private Thread collector;
	private boolean stop;
	public Tracker(InetAddress address) throws IOException {
		this(new InetSocketAddress(address, DEFAULT_TRACKER_PORT),
			DEFAULT_VERSION_STRING);
	}

	public Tracker(InetSocketAddress address) throws IOException {
		this(address, DEFAULT_VERSION_STRING);
	}
	public Tracker(InetSocketAddress address, String version)
		throws IOException {
		this.address = address;

		this.torrents = new ConcurrentHashMap<String, TrackedTorrent>();
		this.connection = new SocketConnection(
				new TrackerService(version, this.torrents));
	}

	public URL getAnnounceUrl() {
		try {
			return new URL("http",
				this.address.getAddress().getCanonicalHostName(),
				this.address.getPort(),
				Tracker.ANNOUNCE_URL);
		} catch (MalformedURLException mue) {
			logger.error("Could not build tracker URL: {}!", mue, mue);
		}

		return null;
	}

	public void start() {
		if (this.tracker == null || !this.tracker.isAlive()) {
			this.tracker = new TrackerThread();
			this.tracker.setName("tracker:" + this.address.getPort());
			this.tracker.start();
		}

		if (this.collector == null || !this.collector.isAlive()) {
			this.collector = new PeerCollectorThread();
			this.collector.setName("peer-collector:" + this.address.getPort());
			this.collector.start();
		}
	}
	public void stop() {
		this.stop = true;

		try {
			this.connection.close();
			logger.info("BitTorrent tracker closed.");
		} catch (IOException ioe) {
			logger.error("Could not stop the tracker: {}!", ioe.getMessage());
		}

		if (this.collector != null && this.collector.isAlive()) {
			this.collector.interrupt();
			logger.info("Peer collection terminated.");
		}
	}
	public Collection<TrackedTorrent> getTrackedTorrents() {
		return torrents.values();
	}

	public synchronized TrackedTorrent announce(TrackedTorrent torrent) {
		TrackedTorrent existing = this.torrents.get(torrent.getHexInfoHash());

		if (existing != null) {
			logger.warn("Tracker already announced torrent for '{}' " +
				"with hash {}.", existing.getName(), existing.getHexInfoHash());
			return existing;
		}

		this.torrents.put(torrent.getHexInfoHash(), torrent);
		logger.info("Registered new torrent for '{}' with hash {}.",
			torrent.getName(), torrent.getHexInfoHash());
		return torrent;
	}
	public synchronized void remove(Torrent torrent) {
		if (torrent == null) {
			return;
		}

		this.torrents.remove(torrent.getHexInfoHash());
	}
	public synchronized void remove(Torrent torrent, long delay) {
		if (torrent == null) {
			return;
		}

		new Timer().schedule(new TorrentRemoveTimer(this, torrent), delay);
	}
	private static class TorrentRemoveTimer extends TimerTask {

		private Tracker tracker;
		private Torrent torrent;

		TorrentRemoveTimer(Tracker tracker, Torrent torrent) {
			this.tracker = tracker;
			this.torrent = torrent;
		}

		@Override
		public void run() {
			this.tracker.remove(torrent);
		}
	}
	private class TrackerThread extends Thread {

		@Override
		public void run() {
			logger.info("Starting BitTorrent tracker on {}...",
				getAnnounceUrl());

			try {
				connection.connect(address);
			} catch (IOException ioe) {
				logger.error("Could not start the tracker: {}!", ioe.getMessage());
				Tracker.this.stop();
			}
		}
	}
	private class PeerCollectorThread extends Thread {

		private static final int PEER_COLLECTION_FREQUENCY_SECONDS = 15;

		@Override
		public void run() {
			logger.info("Starting tracker peer collection for tracker at {}...",
				getAnnounceUrl());

			while (!stop) {
				for (TrackedTorrent torrent : torrents.values()) {
					torrent.collectUnfreshPeers();
				}

				try {
					Thread.sleep(PeerCollectorThread
							.PEER_COLLECTION_FREQUENCY_SECONDS * 1000);
				} catch (InterruptedException ie) {
				}
			}
		}
	}
}
