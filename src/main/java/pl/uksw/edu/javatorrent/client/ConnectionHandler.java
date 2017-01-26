
package pl.uksw.edu.javatorrent.client;

import pl.uksw.edu.javatorrent.client.peer.SharingPeer;
import pl.uksw.edu.javatorrent.common.Torrent;
import pl.uksw.edu.javatorrent.common.Utils;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.text.ParseException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.*;
public class ConnectionHandler implements Runnable {

	private static final Logger logger =
		LoggerFactory.getLogger(ConnectionHandler.class);

	public static final int PORT_RANGE_START = 49152;
	public static final int PORT_RANGE_END = 65534;

	private static final int OUTBOUND_CONNECTIONS_POOL_SIZE = 20;
	private static final int OUTBOUND_CONNECTIONS_THREAD_KEEP_ALIVE_SECS = 10;

	private static final int CLIENT_KEEP_ALIVE_MINUTES = 3;

	private SharedTorrent torrent;
	private String id;
	private ServerSocketChannel channel;
	private InetSocketAddress address;

	private Set<IncomingConnectionListener> listeners;
	private ExecutorService executor;
	private Thread thread;
	private boolean stop;
	ConnectionHandler(SharedTorrent torrent, String id, InetAddress address)
		throws IOException {
		this.torrent = torrent;
		this.id = id;
		for (int port = ConnectionHandler.PORT_RANGE_START;
				port <= ConnectionHandler.PORT_RANGE_END;
				port++) {
			InetSocketAddress tryAddress =
				new InetSocketAddress(address, port);

			try {
				this.channel = ServerSocketChannel.open();
				this.channel.socket().bind(tryAddress);
				this.channel.configureBlocking(false);
				this.address = tryAddress;
				break;
			} catch (IOException ioe) {
				// Ignore, try next port
				logger.warn("Could not bind to {}, trying next port...", tryAddress);
			}
		}

		if (this.channel == null || !this.channel.socket().isBound()) {
			throw new IOException("No available port for the BitTorrent client!");
		}

		logger.info("Listening for incoming connections on {}.", this.address);

		this.listeners = new HashSet<IncomingConnectionListener>();
		this.executor = null;
		this.thread = null;
	}
	public InetSocketAddress getSocketAddress() {
		return this.address;
	}
	public void register(IncomingConnectionListener listener) {
		this.listeners.add(listener);
	}
	public void start() {
		if (this.channel == null) {
			throw new IllegalStateException(
				"Connection handler cannot be recycled!");
		}

		this.stop = false;

		if (this.executor == null || this.executor.isShutdown()) {
			this.executor = new ThreadPoolExecutor(
				OUTBOUND_CONNECTIONS_POOL_SIZE,
				OUTBOUND_CONNECTIONS_POOL_SIZE,
				OUTBOUND_CONNECTIONS_THREAD_KEEP_ALIVE_SECS,
				TimeUnit.SECONDS,
				new LinkedBlockingQueue<Runnable>(),
				new ConnectorThreadFactory());
		}

		if (this.thread == null || !this.thread.isAlive()) {
			this.thread = new Thread(this);
			this.thread.setName("bt-serve");
			this.thread.start();
		}
	}

	public void stop() {
		this.stop = true;

		if (this.thread != null && this.thread.isAlive()) {
			try {
				this.thread.join();
			} catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
			}
		}

		if (this.executor != null && !this.executor.isShutdown()) {
			this.executor.shutdownNow();
		}

		this.executor = null;
		this.thread = null;
	}

	public void close() throws IOException {
		if (this.channel != null) {
			this.channel.close();
			this.channel = null;
		}
	}

	@Override
	public void run() {
		while (!this.stop) {
			try {
				SocketChannel client = this.channel.accept();
				if (client != null) {
					this.accept(client);
				}
			} catch (SocketTimeoutException ste) {
			} catch (IOException ioe) {
				logger.warn("Unrecoverable error in connection handler", ioe);
				this.stop();
			}

			try {
				Thread.sleep(100);
			} catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
			}
		}
	}

	private String socketRepr(SocketChannel channel) {
		Socket s = channel.socket();
		return String.format("%s:%d%s",
			s.getInetAddress().getHostName(),
			s.getPort(),
			channel.isConnected() ? "+" : "-");
	}
	private void accept(SocketChannel client)
		throws IOException, SocketTimeoutException {
		try {
			logger.debug("New incoming connection, waiting for handshake...");
			Handshake hs = this.validateHandshake(client, null);
			int sent = this.sendHandshake(client);
			logger.trace("Replied to {} with handshake ({} bytes).",
				this.socketRepr(client), sent);

			// Go to non-blocking mode for peer interaction
			client.configureBlocking(false);
			client.socket().setSoTimeout(CLIENT_KEEP_ALIVE_MINUTES*60*1000);
			this.fireNewPeerConnection(client, hs.getPeerId());
		} catch (ParseException pe) {
			logger.info("Invalid handshake from {}: {}",
				this.socketRepr(client), pe.getMessage());
			IOUtils.closeQuietly(client);
		} catch (IOException ioe) {
			logger.warn("An error occured while reading an incoming " +
					"handshake: {}", ioe.getMessage());
			if (client.isConnected()) {
				IOUtils.closeQuietly(client);
			}
		}
	}
	public boolean isAlive() {
		return this.executor != null &&
			!this.executor.isShutdown() &&
			!this.executor.isTerminated();
	}
	public void connect(SharingPeer peer) {
		if (!this.isAlive()) {
			throw new IllegalStateException(
				"Connection handler is not accepting new peers at this time!");
		}

		this.executor.submit(new ConnectorTask(this, peer));
	}
	private Handshake validateHandshake(SocketChannel channel, byte[] peerId)
		throws IOException, ParseException {
		ByteBuffer len = ByteBuffer.allocate(1);
		ByteBuffer data;
		logger.trace("Reading handshake size (1 byte) from {}...", this.socketRepr(channel));
		if (channel.read(len) < len.capacity()) {
			throw new IOException("Handshake size read underrrun");
		}

		len.rewind();
		int pstrlen = len.get();

		data = ByteBuffer.allocate(Handshake.BASE_HANDSHAKE_LENGTH + pstrlen);
		data.put((byte)pstrlen);
		int expected = data.remaining();
		int read = channel.read(data);
		if (read < expected) {
			throw new IOException("Handshake data read underrun (" +
				read + " < " + expected + " bytes)");
		}
		data.rewind();
		Handshake hs = Handshake.parse(data);
		if (!Arrays.equals(hs.getInfoHash(), this.torrent.getInfoHash())) {
			throw new ParseException("Handshake for unknow torrent " +
					Utils.bytesToHex(hs.getInfoHash()) +
					" from " + this.socketRepr(channel) + ".", pstrlen + 9);
		}

		if (peerId != null && !Arrays.equals(hs.getPeerId(), peerId)) {
			throw new ParseException("Announced peer ID " +
					Utils.bytesToHex(hs.getPeerId()) +
					" did not match expected peer ID " +
					Utils.bytesToHex(peerId) + ".", pstrlen + 29);
		}

		return hs;
	}
	private int sendHandshake(SocketChannel channel) throws IOException {
		return channel.write(
			Handshake.craft(
				this.torrent.getInfoHash(),
				this.id.getBytes(Torrent.BYTE_ENCODING)).getData());
	}
	private void fireNewPeerConnection(SocketChannel channel, byte[] peerId) {
		for (IncomingConnectionListener listener : this.listeners) {
			listener.handleNewPeerConnection(channel, peerId);
		}
	}

	private void fireFailedConnection(SharingPeer peer, Throwable cause) {
		for (IncomingConnectionListener listener : this.listeners) {
			listener.handleFailedConnection(peer, cause);
		}
	}
	private static class ConnectorThreadFactory implements ThreadFactory {

		private int number = 0;

		@Override
		public Thread newThread(Runnable r) {
			Thread t = new Thread(r);
			t.setName("bt-connect-" + ++this.number);
			return t;
		}
	}
	private static class ConnectorTask implements Runnable {

		private final ConnectionHandler handler;
		private final SharingPeer peer;

		private ConnectorTask(ConnectionHandler handler, SharingPeer peer) {
			this.handler = handler;
			this.peer = peer;
		}

		@Override
		public void run() {
			InetSocketAddress address =
				new InetSocketAddress(this.peer.getIp(), this.peer.getPort());
			SocketChannel channel = null;

			try {
				logger.info("Connecting to {}...", this.peer);
				channel = SocketChannel.open(address);
				while (!channel.isConnected()) {
					Thread.sleep(10);
				}

				logger.debug("Connected. Sending handshake to {}...", this.peer);
				channel.configureBlocking(true);
				int sent = this.handler.sendHandshake(channel);
				logger.debug("Sent handshake ({} bytes), waiting for response...", sent);
				Handshake hs = this.handler.validateHandshake(channel,
					(this.peer.hasPeerId()
						 ? this.peer.getPeerId().array()
						 : null));
				logger.info("Handshaked with {}, peer ID is {}.",
					this.peer, Utils.bytesToHex(hs.getPeerId()));

				// Go to non-blocking mode for peer interaction
				channel.configureBlocking(false);
				this.handler.fireNewPeerConnection(channel, hs.getPeerId());
			} catch (Exception e) {
				if (channel != null && channel.isConnected()) {
					IOUtils.closeQuietly(channel);
				}
				this.handler.fireFailedConnection(this.peer, e);
			}
		}
	}
}
