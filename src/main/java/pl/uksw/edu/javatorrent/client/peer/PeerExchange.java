
package pl.uksw.edu.javatorrent.client.peer;

import pl.uksw.edu.javatorrent.client.SharedTorrent;
import pl.uksw.edu.javatorrent.common.protocol.PeerMessage;
import pl.uksw.edu.javatorrent.common.protocol.PeerMessage.Type;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.EOFException;
import java.io.IOException;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.text.ParseException;
import java.util.BitSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

class PeerExchange {

	private static final Logger logger =
		LoggerFactory.getLogger(PeerExchange.class);

	private static final int KEEP_ALIVE_IDLE_MINUTES = 2;
	private static final PeerMessage STOP = PeerMessage.KeepAliveMessage.craft();

	private SharingPeer peer;
	private SharedTorrent torrent;
	private SocketChannel channel;

	private Set<MessageListener> listeners;

	private IncomingThread in;
	private OutgoingThread out;
	private BlockingQueue<PeerMessage> sendQueue;
	private volatile boolean stop;
	public PeerExchange(SharingPeer peer, SharedTorrent torrent,
			SocketChannel channel) throws SocketException {
		this.peer = peer;
		this.torrent = torrent;
		this.channel = channel;

		this.listeners = new HashSet<MessageListener>();
		this.sendQueue = new LinkedBlockingQueue<PeerMessage>();

		if (!this.peer.hasPeerId()) {
			throw new IllegalStateException("Peer does not have a " +
					"peer ID. Was the handshake made properly?");
		}

		this.in = new IncomingThread();
		this.in.setName("bt-peer(" +
			this.peer.getShortHexPeerId() + ")-recv");

		this.out = new OutgoingThread();
		this.out.setName("bt-peer(" +
			this.peer.getShortHexPeerId() + ")-send");
		this.out.setDaemon(true);

		this.stop = false;

		logger.debug("Started peer exchange with {} for {}.",
			this.peer, this.torrent);
		BitSet pieces = this.torrent.getCompletedPieces();
		if (pieces.cardinality() > 0) {
			this.send(PeerMessage.BitfieldMessage.craft(pieces, torrent.getPieceCount()));
		}
	}

	public void register(MessageListener listener) {
		this.listeners.add(listener);
	}

	public boolean isConnected() {
		return this.channel.isConnected();
	}
	public void send(PeerMessage message) {
		try {
			this.sendQueue.put(message);
		} catch (InterruptedException ie) {
		}
	}


	public void start() {
		this.in.start();
		this.out.start();
	}
	public void stop() {
		this.stop = true;

		try {
			this.sendQueue.put(STOP);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}

		if (this.channel.isConnected()) {
			IOUtils.closeQuietly(this.channel);
		}

		logger.debug("Peer exchange with {} closed.", this.peer);
	}
	private abstract class RateLimitThread extends Thread {

		protected final Rate rate = new Rate();
		protected long sleep = 1000;
		protected void rateLimit(double maxRate, long messageSize, PeerMessage message) {
			if (message.getType() != Type.PIECE || maxRate <= 0) {
				return;
			}

			try {
				this.rate.add(messageSize);
				if (rate.get() > (maxRate * 1024)) {
					Thread.sleep(this.sleep);
					this.sleep += 50;
				} else {
					this.sleep = this.sleep > 50
						? this.sleep - 50
						: 0;
				}
			} catch (InterruptedException e) {
			}
		}
	}
	private class IncomingThread extends RateLimitThread {
		private long read(Selector selector, ByteBuffer buffer) throws IOException {
			if (selector.select() == 0 || !buffer.hasRemaining()) {
				return 0;
			}

			long size = 0;
			Iterator it = selector.selectedKeys().iterator();
			while (it.hasNext()) {
				SelectionKey key = (SelectionKey) it.next();
				if (key.isValid() && key.isReadable()) {
					int read = ((SocketChannel) key.channel()).read(buffer);
					if (read < 0) {
						throw new IOException("Unexpected end-of-stream while reading");
					}
					size += read;
				}
				it.remove();
			}

			return size;
		}

		private void handleIOE(IOException ioe) {
			logger.debug("Could not read message from {}: {}",
				peer,
				ioe.getMessage() != null
					? ioe.getMessage()
					: ioe.getClass().getName());
			peer.unbind(true);
		}

		@Override
		public void run() {
			ByteBuffer buffer = ByteBuffer.allocateDirect(1*1024*1024);
			Selector selector = null;

			try {
				selector = Selector.open();
				channel.register(selector, SelectionKey.OP_READ);

				while (!stop) {
					buffer.rewind();
					buffer.limit(PeerMessage.MESSAGE_LENGTH_FIELD_SIZE);
					while (!stop && buffer.hasRemaining()) {
						this.read(selector, buffer);
					}
					int pstrlen = buffer.getInt(0);
					buffer.limit(PeerMessage.MESSAGE_LENGTH_FIELD_SIZE + pstrlen);

					long size = 0;
					while (!stop && buffer.hasRemaining()) {
						size += this.read(selector, buffer);
					}

					buffer.rewind();
					
					if (stop) {
						break;
					}
					
					try {
						PeerMessage message = PeerMessage.parse(buffer, torrent);
						logger.trace("Received {} from {}", message, peer);
						this.rateLimit(
							PeerExchange.this.torrent.getMaxDownloadRate(),
							size, message);

						for (MessageListener listener : listeners)
							listener.handleMessage(message);
					} catch (ParseException pe) {
						logger.warn("{}", pe.getMessage());
					}
				}
			} catch (IOException ioe) {
				this.handleIOE(ioe);
			} finally {
				try {
					if (selector != null) {
						selector.close();
					}
				} catch (IOException ioe) {
					this.handleIOE(ioe);
				}
			}
		}
	}
	private class OutgoingThread extends RateLimitThread {

		@Override
		public void run() {
			try {
				while (!stop || (stop && sendQueue.size() > 0)) {
					try {
						PeerMessage message = sendQueue.poll(
								PeerExchange.KEEP_ALIVE_IDLE_MINUTES,
								TimeUnit.MINUTES);

						if (message == STOP) {
							return;
						}

						if (message == null) {
							message = PeerMessage.KeepAliveMessage.craft();
						}

						logger.trace("Sending {} to {}", message, peer);

						ByteBuffer data = message.getData();
						long size = 0;
						while (!stop && data.hasRemaining()) {
						    int written = channel.write(data);
						    size += written;
							if (written < 0) {
								throw new EOFException(
									"Reached end of stream while writing");
							}
						}


						this.rateLimit(PeerExchange.this.torrent.getMaxUploadRate(),
							size, message);
					} catch (InterruptedException ie) {

					}
				}
			} catch (IOException ioe) {
				logger.debug("Could not send message to {}: {}",
					peer,
					ioe.getMessage() != null
						? ioe.getMessage()
						: ioe.getClass().getName());
				peer.unbind(true);
			}
		}
	}
}
