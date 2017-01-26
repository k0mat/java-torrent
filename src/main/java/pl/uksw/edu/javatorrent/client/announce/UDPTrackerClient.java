
package pl.uksw.edu.javatorrent.client.announce;

import pl.uksw.edu.javatorrent.client.SharedTorrent;
import pl.uksw.edu.javatorrent.common.Peer;
import pl.uksw.edu.javatorrent.common.protocol.TrackerMessage;
import pl.uksw.edu.javatorrent.common.protocol.TrackerMessage.*;
import pl.uksw.edu.javatorrent.common.protocol.udp.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.UnsupportedAddressTypeException;
import java.util.Calendar;
import java.util.Date;
import java.util.Random;

public class UDPTrackerClient extends TrackerClient {

	protected static final Logger logger =
		LoggerFactory.getLogger(UDPTrackerClient.class);

	private static final int UDP_BASE_TIMEOUT_SECONDS = 15;

	private static final int UDP_MAX_TRIES = 8;

	private static final int UDP_MAX_TRIES_ON_STOPPED = 1;

	private static final int UDP_PACKET_LENGTH = 512;

	private final InetSocketAddress address;
	private final Random random;

	private DatagramSocket socket;
	private Date connectionExpiration;
	private long connectionId;
	private int transactionId;
	private boolean stop;

	private enum State {
		CONNECT_REQUEST,
		ANNOUNCE_REQUEST;
	};

	protected UDPTrackerClient(SharedTorrent torrent, Peer peer, URI tracker)
		throws UnknownHostException {
		super(torrent, peer, tracker);

		if (! (InetAddress.getByName(peer.getIp()) instanceof Inet4Address)) {
			throw new UnsupportedAddressTypeException();
		}

		this.address = new InetSocketAddress(
			tracker.getHost(),
			tracker.getPort());

		this.socket = null;
		this.random = new Random();
		this.connectionExpiration = null;
		this.stop = false;
	}

	@Override
	public void announce(AnnounceRequestMessage.RequestEvent event,
		boolean inhibitEvents) throws AnnounceException {
		logger.info("Announcing{} to tracker with {}U/{}D/{}L bytes...",
			new Object[] {
				this.formatAnnounceEvent(event),
				this.torrent.getUploaded(),
				this.torrent.getDownloaded(),
				this.torrent.getLeft()
			});

		State state = State.CONNECT_REQUEST;
		int maxAttempts = AnnounceRequestMessage.RequestEvent
			.STOPPED.equals(event)
			? UDP_MAX_TRIES_ON_STOPPED
			: UDP_MAX_TRIES;
		int attempts = -1;

		try {
			this.socket = new DatagramSocket();
			this.socket.connect(this.address);

			while (++attempts <= maxAttempts) {
				this.transactionId = this.random.nextInt();
				if (this.connectionExpiration != null) {
					if (new Date().before(this.connectionExpiration)) {
						state = State.ANNOUNCE_REQUEST;
					} else {
						logger.debug("Announce connection ID expired, " +
							"reconnecting with tracker...");
					}
				}

				switch (state) {
					case CONNECT_REQUEST:
						this.send(UDPConnectRequestMessage
							.craft(this.transactionId).getData());

						try {
							this.handleTrackerConnectResponse(
								UDPTrackerMessage.UDPTrackerResponseMessage
									.parse(this.recv(attempts)));
							attempts = -1;
						} catch (SocketTimeoutException ste) {
							if (stop) {
								return;
							}
						}
						break;

					case ANNOUNCE_REQUEST:
						this.send(this.buildAnnounceRequest(event).getData());

						try {
							this.handleTrackerAnnounceResponse(
								UDPTrackerMessage.UDPTrackerResponseMessage
									.parse(this.recv(attempts)), inhibitEvents);

							return;
						} catch (SocketTimeoutException ste) {

							if (stop) {
								return;
							}
						}
						break;
					default:
						throw new IllegalStateException("Invalid announce state!");
				}
			}

			throw new AnnounceException("Timeout while announcing" +
				this.formatAnnounceEvent(event) + " to tracker!");
		} catch (IOException ioe) {
			throw new AnnounceException("Error while announcing" +
				this.formatAnnounceEvent(event) +
				" to tracker: " + ioe.getMessage(), ioe);
		} catch (MessageValidationException mve) {
			throw new AnnounceException("Tracker message violates expected " +
				"protocol (" + mve.getMessage() + ")", mve);
		}
	}


	@Override
	protected void handleTrackerAnnounceResponse(TrackerMessage message,
		boolean inhibitEvents) throws AnnounceException {
		this.validateTrackerResponse(message);
		super.handleTrackerAnnounceResponse(message, inhibitEvents);
	}


	@Override
	protected void close() {
		this.stop = true;

		if (this.socket != null && !this.socket.isClosed()) {
			this.socket.close();
		}
	}

	private UDPAnnounceRequestMessage buildAnnounceRequest(
		AnnounceRequestMessage.RequestEvent event) {
		return UDPAnnounceRequestMessage.craft(
			this.connectionId,
			transactionId,
			this.torrent.getInfoHash(),
			this.peer.getPeerId().array(),
			this.torrent.getDownloaded(),
			this.torrent.getUploaded(),
			this.torrent.getLeft(),
			event,
			this.peer.getAddress(),
			0,
			TrackerMessage.AnnounceRequestMessage.DEFAULT_NUM_WANT,
			this.peer.getPort());
	}


	private void validateTrackerResponse(TrackerMessage message)
		throws AnnounceException {
		if (message instanceof ErrorMessage) {
			throw new AnnounceException(((ErrorMessage)message).getReason());
		}

		if (message instanceof UDPTrackerMessage &&
			(((UDPTrackerMessage)message).getTransactionId() != this.transactionId)) {
			throw new AnnounceException("Invalid transaction ID!");
		}
	}

	private void handleTrackerConnectResponse(TrackerMessage message)
		throws AnnounceException {
		this.validateTrackerResponse(message);

		if (! (message instanceof ConnectionResponseMessage)) {
			throw new AnnounceException("Unexpected tracker message type " +
				message.getType().name() + "!");
		}

		UDPConnectResponseMessage connectResponse =
			(UDPConnectResponseMessage)message;

		this.connectionId = connectResponse.getConnectionId();
		Calendar now = Calendar.getInstance();
		now.add(Calendar.MINUTE, 1);
		this.connectionExpiration = now.getTime();
	}

	private void send(ByteBuffer data) {
		try {
			this.socket.send(new DatagramPacket(
				data.array(),
				data.capacity(),
				this.address));
		} catch (IOException ioe) {
			logger.warn("Error sending datagram packet to tracker at {}: {}.",
				this.address, ioe.getMessage());
		}
	}

	private ByteBuffer recv(int attempt)
		throws IOException, SocketException, SocketTimeoutException {
		int timeout = UDP_BASE_TIMEOUT_SECONDS * (int)Math.pow(2, attempt);
		logger.trace("Setting receive timeout to {}s for attempt {}...",
			timeout, attempt);
		this.socket.setSoTimeout(timeout * 1000);

		try {
			DatagramPacket p = new DatagramPacket(
				new byte[UDP_PACKET_LENGTH],
				UDP_PACKET_LENGTH);
			this.socket.receive(p);
			return ByteBuffer.wrap(p.getData(), 0, p.getLength());
		} catch (SocketTimeoutException ste) {
			throw ste;
		}
	}
}
