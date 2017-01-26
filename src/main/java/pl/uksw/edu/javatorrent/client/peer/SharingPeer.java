
package pl.uksw.edu.javatorrent.client.peer;

import pl.uksw.edu.javatorrent.client.Piece;
import pl.uksw.edu.javatorrent.client.SharedTorrent;
import pl.uksw.edu.javatorrent.common.Peer;
import pl.uksw.edu.javatorrent.common.protocol.PeerMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Serializable;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class SharingPeer extends Peer implements MessageListener {

	private static final Logger logger =
		LoggerFactory.getLogger(SharingPeer.class);

	private static final int MAX_PIPELINED_REQUESTS = 5;

	private boolean choking;
	private boolean interesting;

	private boolean choked;
	private boolean interested;

	private SharedTorrent torrent;
	private BitSet availablePieces;

	private Piece requestedPiece;
	private int lastRequestedOffset;

	private BlockingQueue<PeerMessage.RequestMessage> requests;
	private volatile boolean downloading;

	private PeerExchange exchange;
	private Rate download;
	private Rate upload;

	private Set<PeerActivityListener> listeners;

	private Object requestsLock, exchangeLock;
	public SharingPeer(String ip, int port, ByteBuffer peerId,
			SharedTorrent torrent) {
		super(ip, port, peerId);

		this.torrent = torrent;
		this.listeners = new HashSet<PeerActivityListener>();
		this.availablePieces = new BitSet(this.torrent.getPieceCount());

		this.requestsLock = new Object();
		this.exchangeLock = new Object();

		this.reset();
		this.requestedPiece = null;
	}
	public void register(PeerActivityListener listener) {
		this.listeners.add(listener);
	}

	public Rate getDLRate() {
		return this.download;
	}

	public Rate getULRate() {
		return this.upload;
	}
	public synchronized void reset() {
		this.choking = true;
		this.interesting = false;
		this.choked = true;
		this.interested = false;

		this.exchange = null;

		this.requests = null;
		this.lastRequestedOffset = 0;
		this.downloading = false;
	}
	public void choke() {
		if (!this.choking) {
			logger.trace("Choking {}", this);
			this.send(PeerMessage.ChokeMessage.craft());
			this.choking = true;
		}
	}
	public void unchoke() {
		if (this.choking) {
			logger.trace("Unchoking {}", this);
			this.send(PeerMessage.UnchokeMessage.craft());
			this.choking = false;
		}
	}

	public boolean isChoking() {
		return this.choking;
	}


	public void interesting() {
		if (!this.interesting) {
			logger.trace("Telling {} we're interested.", this);
			this.send(PeerMessage.InterestedMessage.craft());
			this.interesting = true;
		}
	}

	public void notInteresting() {
		if (this.interesting) {
			logger.trace("Telling {} we're no longer interested.", this);
			this.send(PeerMessage.NotInterestedMessage.craft());
			this.interesting = false;
		}
	}

	public boolean isInteresting() {
		return this.interesting;
	}


	public boolean isChoked() {
		return this.choked;
	}

	public boolean isInterested() {
		return this.interested;
	}
	public BitSet getAvailablePieces() {
		synchronized (this.availablePieces) {
			return (BitSet)this.availablePieces.clone();
		}
	}

	public Piece getRequestedPiece() {
		return this.requestedPiece;
	}
	public synchronized boolean isSeed() {
		return this.torrent.getPieceCount() > 0 &&
			this.getAvailablePieces().cardinality() ==
				this.torrent.getPieceCount();
	}

	public synchronized void bind(SocketChannel channel) throws SocketException {
		this.unbind(true);

		this.exchange = new PeerExchange(this, this.torrent, channel);
		this.exchange.register(this);
		this.exchange.start();

		this.download = new Rate();
		this.download.reset();

		this.upload = new Rate();
		this.upload.reset();
	}

	public boolean isConnected() {
		synchronized (this.exchangeLock) {
			return this.exchange != null && this.exchange.isConnected();
		}
	}

	public void unbind(boolean force) {
		if (!force) {
			this.cancelPendingRequests();
			this.send(PeerMessage.NotInterestedMessage.craft());
		}

		synchronized (this.exchangeLock) {
			if (this.exchange != null) {
				this.exchange.stop();
				this.exchange = null;
			}
		}

		this.firePeerDisconnected();
		this.requestedPiece = null;
	}

	public void send(PeerMessage message) throws IllegalStateException {
		if (this.isConnected()) {
			this.exchange.send(message);
		} else {
			logger.warn("Attempting to send a message to non-connected peer {}!", this);
		}
	}

	public synchronized void downloadPiece(Piece piece)
		throws IllegalStateException {
		if (this.isDownloading()) {
			IllegalStateException up = new IllegalStateException(
					"Trying to download a piece while previous " +
					"download not completed!");
			logger.warn("What's going on? {}", up.getMessage(), up);
			throw up; // ah ah.
		}

		this.requests = new LinkedBlockingQueue<PeerMessage.RequestMessage>(
				SharingPeer.MAX_PIPELINED_REQUESTS);
		this.requestedPiece = piece;
		this.lastRequestedOffset = 0;
		this.requestNextBlocks();
	}

	public boolean isDownloading() {
		return this.downloading;
	}

	private void requestNextBlocks() {
		synchronized (this.requestsLock) {
			if (this.requests == null || this.requestedPiece == null) {
				return;
			}

			while (this.requests.remainingCapacity() > 0 &&
					this.lastRequestedOffset < this.requestedPiece.size()) {
				PeerMessage.RequestMessage request = PeerMessage.RequestMessage
					.craft(
						this.requestedPiece.getIndex(),
						this.lastRequestedOffset,
						Math.min(
							(int)(this.requestedPiece.size() -
								this.lastRequestedOffset),
							PeerMessage.RequestMessage.DEFAULT_REQUEST_SIZE));
				this.requests.add(request);
				this.send(request);
				this.lastRequestedOffset += request.getLength();
			}

			this.downloading = this.requests.size() > 0;
		}
	}
	private void removeBlockRequest(PeerMessage.PieceMessage message) {
		synchronized (this.requestsLock) {
			if (this.requests == null) {
				return;
			}

			for (PeerMessage.RequestMessage request : this.requests) {
				if (request.getPiece() == message.getPiece() &&
						request.getOffset() == message.getOffset()) {
					this.requests.remove(request);
					break;
				}
			}

			this.downloading = this.requests.size() > 0;
		}
	}
	public Set<PeerMessage.RequestMessage> cancelPendingRequests() {
		synchronized (this.requestsLock) {
			Set<PeerMessage.RequestMessage> requests =
				new HashSet<PeerMessage.RequestMessage>();

			if (this.requests != null) {
				for (PeerMessage.RequestMessage request : this.requests) {
					this.send(PeerMessage.CancelMessage.craft(request.getPiece(),
								request.getOffset(), request.getLength()));
					requests.add(request);
				}
			}

			this.requests = null;
			this.downloading = false;
			return requests;
		}
	}
	@Override
	public synchronized void handleMessage(PeerMessage msg) {
		switch (msg.getType()) {
			case KEEP_ALIVE:
				break;
			case CHOKE:
				this.choked = true;
				this.firePeerChoked();
				this.cancelPendingRequests();
				break;
			case UNCHOKE:
				this.choked = false;
				logger.trace("Peer {} is now accepting requests.", this);
				this.firePeerReady();
				break;
			case INTERESTED:
				this.interested = true;
				break;
			case NOT_INTERESTED:
				this.interested = false;
				break;
			case HAVE:
				PeerMessage.HaveMessage have = (PeerMessage.HaveMessage)msg;
				Piece havePiece = this.torrent.getPiece(have.getPieceIndex());

				synchronized (this.availablePieces) {
					this.availablePieces.set(havePiece.getIndex());
					logger.trace("Peer {} now has {} [{}/{}].",
						new Object[] {
							this,
							havePiece,
							this.availablePieces.cardinality(),
							this.torrent.getPieceCount()
						});
				}

				this.firePieceAvailabity(havePiece);
				break;
			case BITFIELD:
				PeerMessage.BitfieldMessage bitfield =
					(PeerMessage.BitfieldMessage)msg;

				synchronized (this.availablePieces) {
					this.availablePieces.or(bitfield.getBitfield());
					logger.trace("Recorded bitfield from {} with {} " +
						"pieces(s) [{}/{}].",
						new Object[] {
							this,
							bitfield.getBitfield().cardinality(),
							this.availablePieces.cardinality(),
							this.torrent.getPieceCount()
						});
				}

				this.fireBitfieldAvailabity();
				break;
			case REQUEST:
				PeerMessage.RequestMessage request =
					(PeerMessage.RequestMessage)msg;
				Piece rp = this.torrent.getPiece(request.getPiece());
				if (this.isChoking() || !rp.isValid()) {
					logger.warn("Peer {} violated protocol, " +
						"terminating exchange.", this);
					this.unbind(true);
					break;
				}

				if (request.getLength() >
						PeerMessage.RequestMessage.MAX_REQUEST_SIZE) {
					logger.warn("Peer {} requested a block too big, " +
						"terminating exchange.", this);
					this.unbind(true);
					break;
				}
				try {
					ByteBuffer block = rp.read(request.getOffset(),
									request.getLength());
					this.send(PeerMessage.PieceMessage.craft(request.getPiece(),
								request.getOffset(), block));
					this.upload.add(block.capacity());

					if (request.getOffset() + request.getLength() == rp.size()) {
						this.firePieceSent(rp);
					}
				} catch (IOException ioe) {
					this.fireIOException(new IOException(
							"Error while sending piece block request!", ioe));
				}

				break;
			case PIECE:
				PeerMessage.PieceMessage piece = (PeerMessage.PieceMessage)msg;
				Piece p = this.torrent.getPiece(piece.getPiece());
				this.removeBlockRequest(piece);
				this.download.add(piece.getBlock().capacity());

				try {
					synchronized (p) {
						if (p.isValid()) {
							this.requestedPiece = null;
							this.cancelPendingRequests();
							this.firePeerReady();
							logger.debug("Discarding block for already completed " + p);
							break;
						}

						p.record(piece.getBlock(), piece.getOffset());
						if (piece.getOffset() + piece.getBlock().capacity()
								== p.size()) {
							p.validate();
							this.firePieceCompleted(p);
							this.requestedPiece = null;
							this.firePeerReady();
						} else {
							this.requestNextBlocks();
						}
					}
				} catch (IOException ioe) {
					this.fireIOException(new IOException(
							"Error while storing received piece block!", ioe));
					break;
				}
				break;
			case CANCEL:
				break;
		}
	}

	private void firePeerChoked() {
		for (PeerActivityListener listener : this.listeners) {
			listener.handlePeerChoked(this);
		}
	}

	private void firePeerReady() {
		for (PeerActivityListener listener : this.listeners) {
			listener.handlePeerReady(this);
		}
	}

	private void firePieceAvailabity(Piece piece) {
		for (PeerActivityListener listener : this.listeners) {
			listener.handlePieceAvailability(this, piece);
		}
	}

	private void fireBitfieldAvailabity() {
		for (PeerActivityListener listener : this.listeners) {
			listener.handleBitfieldAvailability(this,
					this.getAvailablePieces());
		}
	}
	private void firePieceSent(Piece piece) {
		for (PeerActivityListener listener : this.listeners) {
			listener.handlePieceSent(this, piece);
		}
	}

	private void firePieceCompleted(Piece piece) throws IOException {
		for (PeerActivityListener listener : this.listeners) {
			listener.handlePieceCompleted(this, piece);
		}
	}
	private void firePeerDisconnected() {
		for (PeerActivityListener listener : this.listeners) {
			listener.handlePeerDisconnected(this);
		}
	}

	private void fireIOException(IOException ioe) {
		for (PeerActivityListener listener : this.listeners) {
			listener.handleIOException(this, ioe);
		}
	}
	public static class DLRateComparator
			implements Comparator<SharingPeer>, Serializable {

		private static final long serialVersionUID = 96307229964730L;

		@Override
		public int compare(SharingPeer a, SharingPeer b) {
			return Rate.RATE_COMPARATOR.compare(a.getDLRate(), b.getDLRate());
		}
	}

	public static class ULRateComparator
			implements Comparator<SharingPeer>, Serializable {

		private static final long serialVersionUID = 38794949747717L;

		@Override
		public int compare(SharingPeer a, SharingPeer b) {
			return Rate.RATE_COMPARATOR.compare(a.getULRate(), b.getULRate());
		}
	}

	public String toString() {
		return new StringBuilder(super.toString())
			.append(" [")
			.append((this.choked ? "C" : "c"))
			.append((this.interested ? "I" : "i"))
			.append("|")
			.append((this.choking ? "C" : "c"))
			.append((this.interesting ? "I" : "i"))
			.append("|")
			.append(this.availablePieces.cardinality())
			.append("]")
			.toString();
	}
}
