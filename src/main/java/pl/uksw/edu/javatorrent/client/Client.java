
package pl.uksw.edu.javatorrent.client;

import pl.uksw.edu.javatorrent.client.announce.Announce;
import pl.uksw.edu.javatorrent.client.announce.AnnounceException;
import pl.uksw.edu.javatorrent.client.announce.AnnounceResponseListener;
import pl.uksw.edu.javatorrent.client.peer.PeerActivityListener;
import pl.uksw.edu.javatorrent.client.peer.SharingPeer;
import pl.uksw.edu.javatorrent.common.Peer;
import pl.uksw.edu.javatorrent.common.Torrent;
import pl.uksw.edu.javatorrent.common.protocol.PeerMessage;
import pl.uksw.edu.javatorrent.common.protocol.TrackerMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class Client extends Observable implements Runnable,
		AnnounceResponseListener, IncomingConnectionListener, PeerActivityListener {

	private static final Logger logger =
			LoggerFactory.getLogger(Client.class);
	private static final int UNCHOKING_FREQUENCY = 3;
	private static final int OPTIMISTIC_UNCHOKE_ITERATIONS = 3;

	private static final int RATE_COMPUTATION_ITERATIONS = 2;
	private static final int MAX_DOWNLOADERS_UNCHOKE = 4;

	public enum ClientState {
		WAITING,
		VALIDATING,
		SHARING,
		SEEDING,
		ERROR,
		DONE;
	};

	private static final String BITTORRENT_ID_PREFIX = "-TO0042-";

	private SharedTorrent torrent;
	private ClientState state;
	private Peer self;

	private Thread thread;
	private boolean stop;
	private long seed;

	private ConnectionHandler service;
	private Announce announce;
	private ConcurrentMap<String, SharingPeer> peers;
	private ConcurrentMap<String, SharingPeer> connected;
	private Random random;
	public Client(InetAddress address, SharedTorrent torrent)
			throws UnknownHostException, IOException {
		this.torrent = torrent;
		this.state = ClientState.WAITING;

		String id = Client.BITTORRENT_ID_PREFIX + UUID.randomUUID()
				.toString().split("-")[4];
		this.service = new ConnectionHandler(this.torrent, id, address);
		this.service.register(this);

		this.self = new Peer(
				this.service.getSocketAddress()
						.getAddress().getHostAddress(),
				this.service.getSocketAddress().getPort(),
				ByteBuffer.wrap(id.getBytes(Torrent.BYTE_ENCODING)));
		this.announce = new Announce(this.torrent, this.self);
		this.announce.register(this);

		logger.info("K0Torrent client [{}] przez {} rozpoczety i " +
						"nadsluchuje {}:{}...",
				new Object[] {
						this.self.getShortHexPeerId(),
						this.torrent.getName(),
						this.self.getIp(),
						this.self.getPort()
				});

		this.peers = new ConcurrentHashMap<String, SharingPeer>();
		this.connected = new ConcurrentHashMap<String, SharingPeer>();
		this.random = new Random(System.currentTimeMillis());
	}
	public void setMaxDownloadRate(double rate) {
		this.torrent.setMaxDownloadRate(rate);
	}

	public void setMaxUploadRate(double rate) {
		this.torrent.setMaxUploadRate(rate);
	}
	public Peer getPeerSpec() {
		return this.self;
	}
	public SharedTorrent getTorrent() {
		return this.torrent;
	}
	public Set<SharingPeer> getPeers() {
		return new HashSet<SharingPeer>(this.peers.values());
	}
	private synchronized void setState(ClientState state) {
		if (this.state != state) {
			this.setChanged();
		}
		this.state = state;
		this.notifyObservers(this.state);
	}
	public ClientState getState() {
		return this.state;
	}
	public void download() {
		this.share(0);
	}
	public void share() {
		this.share(-1);
	}
	public synchronized void share(int seed) {
		this.seed = seed;
		this.stop = false;

		if (this.thread == null || !this.thread.isAlive()) {
			this.thread = new Thread(this);
			this.thread.setName("bt-client(" +
					this.self.getShortHexPeerId() + ")");
			this.thread.start();
		}
	}

	public void stop() {
		this.stop(true);
	}

	public void stop(boolean wait) {
		this.stop = true;

		if (this.thread != null && this.thread.isAlive()) {
			this.thread.interrupt();
			if (wait) {
				this.waitForCompletion();
			}
		}

		this.thread = null;
	}
	public void waitForCompletion() {
		if (this.thread != null && this.thread.isAlive()) {
			try {
				this.thread.join();
			} catch (InterruptedException ie) {
				logger.error(ie.getMessage(), ie);
			}
		}
	}
	public boolean isSeed() {
		return this.torrent.isComplete();
	}

	@Override
	public void run() {
		// First, analyze the torrent's local data.
		try {
			this.setState(ClientState.VALIDATING);
			this.torrent.init();
		} catch (IOException ioe) {
			logger.warn("Bład podczas inicjalizacji danych: {}!",
					ioe.getMessage(), ioe);
		} catch (InterruptedException ie) {
			logger.warn("Przerwano podczas inicjalizacji. " +
					"Nagle przerwanie.");
		} finally {
			if (!this.torrent.isInitialized()) {
				try {
					this.service.close();
				} catch (IOException ioe) {
					logger.warn("Blad podczas zwalniania kanalu: {}!",
							ioe.getMessage(), ioe);
				}

				this.setState(ClientState.ERROR);
				this.torrent.close();
				return;
			}
		}


		if (this.torrent.isComplete()) {
			this.seed();
		} else {
			this.setState(ClientState.SHARING);
		}


		if (this.stop) {
			logger.info("Pobieranie zakonczylo sie, wysylanie(Seeding) nie jest wymagane.");
			this.finish();
			return;
		}

		this.announce.start();
		this.service.start();

		int optimisticIterations = 0;
		int rateComputationIterations = 0;

		while (!this.stop) {
			optimisticIterations =
					(optimisticIterations == 0 ?
							Client.OPTIMISTIC_UNCHOKE_ITERATIONS :
							optimisticIterations - 1);

			rateComputationIterations =
					(rateComputationIterations == 0 ?
							Client.RATE_COMPUTATION_ITERATIONS :
							rateComputationIterations - 1);

			try {
				this.unchokePeers(optimisticIterations == 0);
				this.info();
				if (rateComputationIterations == 0) {
					this.resetPeerRates();
				}
			} catch (Exception e) {
				logger.error("Wystapil wyjatek " +
						"Klient sie zapetlil", e);
			}

			try {
				Thread.sleep(Client.UNCHOKING_FREQUENCY*1000);
			} catch (InterruptedException ie) {
				logger.trace("Glowna petla zostla przerwana.");
			}
		}

		logger.debug("Zatrzymywanie usługi  " +
				"i wystapienie wyjatku...");

		this.service.stop();
		try {
			this.service.close();
		} catch (IOException ioe) {
			logger.warn("Blad podczas zwalniania kanalu: {}!",
					ioe.getMessage(), ioe);
		}

		this.announce.stop();


		logger.debug("Zamkniecie wszystkich pozostalych polaczen peer...");
		for (SharingPeer peer : this.connected.values()) {
			peer.unbind(true);
		}

		this.finish();
	}

	private void finish() {
		this.torrent.close();
		if (this.torrent.isFinished()) {
			this.setState(ClientState.DONE);
		} else {
			this.setState(ClientState.ERROR);
		}

		logger.info("Usluga K0Torrent zostala wyrejestrowana.");
	}

	public synchronized void info() {
		float dl = 0;
		float ul = 0;
		for (SharingPeer peer : this.connected.values()) {
			dl += peer.getDLRate().get();
			ul += peer.getULRate().get();
		}

		logger.info("{} {}/{} czesci ({}%) [{}/{}] z {}/{} peerow, predkosc: {}/{} kB/s.",
				new Object[] {
						this.getState().name(),
						this.torrent.getCompletedPieces().cardinality(),
						this.torrent.getPieceCount(),
						String.format("%.2f", this.torrent.getCompletion()),
						this.torrent.getAvailablePieces().cardinality(),
						this.torrent.getRequestedPieces().cardinality(),
						this.connected.size(),
						this.peers.size(),
						String.format("%.2f", dl/1024.0),
						String.format("%.2f", ul/1024.0),
				});
		for (SharingPeer peer : this.connected.values()) {
			Piece piece = peer.getRequestedPiece();
			logger.debug("  | {} {}",
					peer,
					piece != null
							? "(pobieranie " + piece + ")"
							: ""
			);
		}
	}

	private synchronized void resetPeerRates() {
		for (SharingPeer peer : this.connected.values()) {
			peer.getDLRate().reset();
			peer.getULRate().reset();
		}
	}
	private SharingPeer getOrCreatePeer(Peer search) {
		SharingPeer peer;

		synchronized (this.peers) {
			logger.trace("Szukanie {}...", search);
			if (search.hasPeerId()) {
				peer = this.peers.get(search.getHexPeerId());
				if (peer != null) {
					logger.trace("Znaleziono (peer ID): {}.", peer);
					this.peers.put(peer.getHostIdentifier(), peer);
					this.peers.put(search.getHostIdentifier(), peer);
					return peer;
				}
			}

			peer = this.peers.get(search.getHostIdentifier());
			if (peer != null) {
				if (search.hasPeerId()) {
					logger.trace("Zapisywanie ID Peera {} dla {}.",
							search.getHexPeerId(), peer);
					peer.setPeerId(search.getPeerId());
					this.peers.put(search.getHexPeerId(), peer);
				}

				logger.debug("Znaleziono (host ID): {}.", peer);
				return peer;
			}

			peer = new SharingPeer(search.getIp(), search.getPort(),
					search.getPeerId(), this.torrent);
			logger.trace("Tworzenie nowego peera: {}.", peer);

			this.peers.put(peer.getHostIdentifier(), peer);
			if (peer.hasPeerId()) {
				this.peers.put(peer.getHexPeerId(), peer);
			}

			return peer;
		}
	}

	private Comparator<SharingPeer> getPeerRateComparator() {
		if (ClientState.SHARING.equals(this.state)) {
			return new SharingPeer.DLRateComparator();
		} else if (ClientState.SEEDING.equals(this.state)) {
			return new SharingPeer.ULRateComparator();
		} else {
			throw new IllegalStateException("Client niczego nie wysyla " +
					"\n" + "Seeding, nie powinismy porównywać peerow w tym momencie?");
		}
	}

	private synchronized void unchokePeers(boolean optimistic) {

		TreeSet<SharingPeer> bound = new TreeSet<SharingPeer>(
				this.getPeerRateComparator());
		bound.addAll(this.connected.values());

		if (bound.size() == 0) {
			logger.trace("Brak, polaczonych peerow, oczekiwanie.");
			return;
		} else {
			logger.trace("Uruchomienie unchokePeers() dla {} Polaczonych peerow.",
					bound.size());
		}

		int downloaders = 0;
		Set<SharingPeer> choked = new HashSet<SharingPeer>();


		for (SharingPeer peer : bound.descendingSet()) {
			if (downloaders < Client.MAX_DOWNLOADERS_UNCHOKE) {
				if (peer.isChoking()) {
					if (peer.isInterested()) {
						downloaders++;
					}

					peer.unchoke();
				}
			} else {
				choked.add(peer);
			}
		}


		if (choked.size() > 0) {
			SharingPeer randomPeer = choked.toArray(
					new SharingPeer[0])[this.random.nextInt(choked.size())];

			for (SharingPeer peer : choked) {
				if (optimistic && peer == randomPeer) {
					logger.debug("Optymistyczna operacja unchockingu{}.", peer);
					peer.unchoke();
					continue;
				}

				peer.choke();
			}
		}
	}

	@Override
	public void handleAnnounceResponse(int interval, int complete,
									   int incomplete) {
		this.announce.setInterval(interval);
	}

	@Override
	public void handleDiscoveredPeers(List<Peer> peers) {
		if (peers == null || peers.isEmpty()) {

			return;
		}

		logger.info("Otrzymano {} Peera/ow w odpowiedzi od Trackera.", peers.size());

		if (!this.service.isAlive()) {
			logger.warn("Obsluga polaczenia nie jest w tej chwili dostepna");
			return;
		}

		for (Peer peer : peers) {
			SharingPeer match = this.getOrCreatePeer(peer);
			if (this.isSeed()) {
				continue;
			}

			synchronized (match) {
				if (!match.isConnected()) {
					this.service.connect(match);
				}
			}
		}
	}

	@Override
	public void handleNewPeerConnection(SocketChannel channel, byte[] peerId) {
		Peer search = new Peer(
				channel.socket().getInetAddress().getHostAddress(),
				channel.socket().getPort(),
				(peerId != null
						? ByteBuffer.wrap(peerId)
						: (ByteBuffer)null));

		logger.info("Nowe polaczenie peera z  {}...", search);
		SharingPeer peer = this.getOrCreatePeer(search);

		try {
			synchronized (peer) {
				if (peer.isConnected()) {
					logger.info("Poprzednio polaczono z {},zamykanie linka.",
							peer);
					channel.close();
					return;
				}

				peer.register(this);
				peer.bind(channel);
			}

			this.connected.put(peer.getHexPeerId(), peer);
			peer.register(this.torrent);
			logger.debug("Nowe polaczenie peera z {} [{}/{}].",
					new Object[] {
							peer,
							this.connected.size(),
							this.peers.size()
					});
		} catch (Exception e) {
			this.connected.remove(peer.getHexPeerId());
			logger.warn("Nie mozna bylo polaczyc nowego peera " +
					"z {}: {}", peer, e.getMessage());
		}
	}
	@Override
	public void handleFailedConnection(SharingPeer peer, Throwable cause) {
		logger.warn("Nie mozna bylo polaczyc {}: {}.", peer, cause.getMessage());
		this.peers.remove(peer.getHostIdentifier());
		if (peer.hasPeerId()) {
			this.peers.remove(peer.getHexPeerId());
		}
	}


	@Override
	public void handlePeerChoked(SharingPeer peer) {  }

	@Override
	public void handlePeerReady(SharingPeer peer) {  }

	@Override
	public void handlePieceAvailability(SharingPeer peer,
										Piece piece) {  }

	@Override
	public void handleBitfieldAvailability(SharingPeer peer,
										   BitSet availablePieces) { }

	@Override
	public void handlePieceSent(SharingPeer peer,
								Piece piece) {  }

	@Override
	public void handlePieceCompleted(SharingPeer peer, Piece piece)
			throws IOException {
		synchronized (this.torrent) {
			if (piece.isValid()) {
				this.torrent.markCompleted(piece);
				logger.debug("Ukonczone pobieranie od {} do {}. " +
								"Pobrano {}/{} czesci",
						new Object[] {
								piece,
								peer,
								this.torrent.getCompletedPieces().cardinality(),
								this.torrent.getPieceCount()
						});


				PeerMessage have = PeerMessage.HaveMessage.craft(piece.getIndex());
				for (SharingPeer remote : this.connected.values()) {
					remote.send(have);
				}

				this.setChanged();
				this.notifyObservers(this.state);
			} else {
				logger.warn("Pobrano czesci#{} z {} nie sa poprawne ;-(",
						piece.getIndex(), peer);
			}

			if (this.torrent.isComplete()) {
				logger.info("Ostatnia czesc zostala sprawdzona, konczenie pobierania...");

				for (SharingPeer remote : this.connected.values()) {
					if (remote.isDownloading()) {
						int requests = remote.cancelPendingRequests().size();
						logger.info("Anulowano {} Oczekiwanie zadania {}.",
								requests, remote);
					}
				}

				this.torrent.finish();

				try {
					this.announce.getCurrentTrackerClient()
							.announce(TrackerMessage
									.AnnounceRequestMessage
									.RequestEvent.COMPLETED, true);
				} catch (AnnounceException ae) {
					logger.warn("Blad, zakonczenie " +
							"tracker: {}", ae.getMessage());
				}

				logger.info("Pobieranie zostalo zakonczone.");
				this.seed();
			}
		}
	}

	@Override
	public void handlePeerDisconnected(SharingPeer peer) {
		if (this.connected.remove(peer.hasPeerId()
				? peer.getHexPeerId()
				: peer.getHostIdentifier()) != null) {
			logger.debug("Peer {} odlaczony, [{}/{}].",
					new Object[] {
							peer,
							this.connected.size(),
							this.peers.size()
					});
		}

		peer.reset();
	}

	@Override
	public void handleIOException(SharingPeer peer, IOException ioe) {
		logger.warn("I/O error, podczas wymiany danych z {}, " +
				"Zamykanie polaczenia!", peer, ioe.getMessage());
		peer.unbind(true);
	}


	private synchronized void seed() {

		if (ClientState.SEEDING.equals(this.getState())) {
			return;
		}

		logger.info("Ukonczono pobieranie {} czesci.",
				this.torrent.getPieceCount());

		this.setState(ClientState.SEEDING);
		if (this.seed < 0) {
			logger.info("Seeding...");
			return;
		}


		logger.info("Seeding {} sekund...", this.seed);
		Timer timer = new Timer();
		timer.schedule(new ClientShutdown(this, timer), this.seed*1000);
	}

	public static class ClientShutdown extends TimerTask {

		private final Client client;
		private final Timer timer;

		public ClientShutdown(Client client, Timer timer) {
			this.client = client;
			this.timer = timer;
		}

		@Override
		public void run() {
			this.client.stop();
			if (this.timer != null) {
				this.timer.cancel();
			}
		}
	};
}
