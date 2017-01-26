
package pl.uksw.edu.javatorrent.client;

import pl.uksw.edu.javatorrent.bcodec.InvalidBEncodingException;
import pl.uksw.edu.javatorrent.client.peer.PeerActivityListener;
import pl.uksw.edu.javatorrent.client.peer.SharingPeer;
import pl.uksw.edu.javatorrent.client.storage.FileCollectionStorage;
import pl.uksw.edu.javatorrent.client.storage.FileStorage;
import pl.uksw.edu.javatorrent.client.storage.TorrentByteStorage;
import pl.uksw.edu.javatorrent.client.strategy.RequestStrategy;
import pl.uksw.edu.javatorrent.client.strategy.RequestStrategyImplRarest;
import pl.uksw.edu.javatorrent.common.Torrent;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class SharedTorrent extends Torrent implements PeerActivityListener {

	private static final Logger logger =
		LoggerFactory.getLogger(SharedTorrent.class);

	private static final float ENG_GAME_COMPLETION_RATIO = 0.95f;
	private static final RequestStrategy DEFAULT_REQUEST_STRATEGY = new RequestStrategyImplRarest();

	private boolean stop;

	private long uploaded;
	private long downloaded;
	private long left;

	private final TorrentByteStorage bucket;

	private final int pieceLength;
	private final ByteBuffer piecesHashes;

	private boolean initialized;
	private Piece[] pieces;
	private SortedSet<Piece> rarest;
	private BitSet completedPieces;
	private BitSet requestedPieces;
	private RequestStrategy requestStrategy;
	
	private double maxUploadRate = 0.0;
	private double maxDownloadRate = 0.0;

	public SharedTorrent(Torrent torrent, File destDir)
		throws FileNotFoundException, IOException, NoSuchAlgorithmException {
		this(torrent, destDir, false);
	}

	public SharedTorrent(Torrent torrent, File destDir, boolean seeder)
		throws FileNotFoundException, IOException, NoSuchAlgorithmException {
		this(torrent.getEncoded(), destDir, seeder, DEFAULT_REQUEST_STRATEGY);
	}

	public SharedTorrent(Torrent torrent, File destDir, boolean seeder,
			RequestStrategy requestStrategy)
		throws FileNotFoundException, IOException, NoSuchAlgorithmException {
		this(torrent.getEncoded(), destDir, seeder, requestStrategy);
	}


	public SharedTorrent(byte[] torrent, File destDir)
		throws FileNotFoundException, IOException, NoSuchAlgorithmException {
		this(torrent, destDir, false);
	}

	public SharedTorrent(byte[] torrent, File parent, boolean seeder)
		throws FileNotFoundException, IOException, NoSuchAlgorithmException {
		this(torrent, parent, seeder, DEFAULT_REQUEST_STRATEGY);
	}
	public SharedTorrent(byte[] torrent, File parent, boolean seeder,
			RequestStrategy requestStrategy)
		throws FileNotFoundException, IOException, NoSuchAlgorithmException {
		super(torrent, seeder);

		if (parent == null || !parent.isDirectory()) {
			throw new IllegalArgumentException("Invalid parent directory!");
		}

		String parentPath = parent.getCanonicalPath();

		try {
			this.pieceLength = this.decoded_info.get("piece length").getInt();
			this.piecesHashes = ByteBuffer.wrap(this.decoded_info.get("pieces")
					.getBytes());

			if (this.piecesHashes.capacity() / Torrent.PIECE_HASH_SIZE *
					(long)this.pieceLength < this.getSize()) {
				throw new IllegalArgumentException("Torrent size does not " +
						"match the number of pieces and the piece size!");
			}
		} catch (InvalidBEncodingException ibee) {
			throw new IllegalArgumentException(
					"Error reading torrent meta-info fields!");
		}

		List<FileStorage> files = new LinkedList<FileStorage>();
		long offset = 0L;
		for (Torrent.TorrentFile file : this.files) {
			File actual = new File(parent, file.file.getPath());

			if (!actual.getCanonicalPath().startsWith(parentPath)) {
				throw new SecurityException("Torrent file path attempted " +
					"to break directory jail!");
			}

			actual.getParentFile().mkdirs();
			files.add(new FileStorage(actual, offset, file.size));
			offset += file.size;
		}
		this.bucket = new FileCollectionStorage(files, this.getSize());

		this.stop = false;

		this.uploaded = 0;
		this.downloaded = 0;
		this.left = this.getSize();

		this.initialized = false;
		this.pieces = new Piece[0];
		this.rarest = Collections.synchronizedSortedSet(new TreeSet<Piece>());
		this.completedPieces = new BitSet();
		this.requestedPieces = new BitSet();

		//TODO: should switch to guice
		this.requestStrategy = requestStrategy;
	}
	public static SharedTorrent fromFile(File source, File parent)
		throws IOException, NoSuchAlgorithmException {
		byte[] data = FileUtils.readFileToByteArray(source);
		return new SharedTorrent(data, parent);
	}

	public double getMaxUploadRate() {
		return this.maxUploadRate;
	}
	public void setMaxUploadRate(double rate) {
		this.maxUploadRate = rate;
	}

	public double getMaxDownloadRate() {
		return this.maxDownloadRate;
	}

	public void setMaxDownloadRate(double rate) {
		this.maxDownloadRate = rate;
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


	public boolean isInitialized() {
		return this.initialized;
	}


	public void stop() {
		this.stop = true;
	}

	public synchronized void init() throws InterruptedException, IOException {
		if (this.isInitialized()) {
			throw new IllegalStateException("Torrent was already initialized!");
		}

		int threads = getHashingThreadsCount();
		int nPieces = (int) (Math.ceil(
				(double)this.getSize() / this.pieceLength));
		int step = 10;

		this.pieces = new Piece[nPieces];
		this.completedPieces = new BitSet(nPieces);
		this.piecesHashes.clear();

		ExecutorService executor = Executors.newFixedThreadPool(threads);
		List<Future<Piece>> results = new LinkedList<Future<Piece>>();

		try {
			logger.info("Analyzing local data for {} with {} threads ({} pieces)...",
				new Object[] { this.getName(), threads, nPieces });
			for (int idx=0; idx<nPieces; idx++) {
				byte[] hash = new byte[Torrent.PIECE_HASH_SIZE];
				this.piecesHashes.get(hash);

				long off = ((long)idx) * this.pieceLength;
				long len = Math.min(
					this.bucket.size() - off,
					this.pieceLength);

				this.pieces[idx] = new Piece(this.bucket, idx, off, len, hash,
					this.isSeeder());

				Callable<Piece> hasher = new Piece.CallableHasher(this.pieces[idx]);
				results.add(executor.submit(hasher));

				if (results.size() >= threads) {
					this.validatePieces(results);
				}

				if (idx / (float)nPieces * 100f > step) {
					logger.info("  ... {}% complete", step);
					step += 10;
				}
			}

			this.validatePieces(results);
		} finally {
			executor.shutdown();
			while (!executor.isTerminated()) {
				if (this.stop) {
					throw new InterruptedException("Torrent data analysis " +
						"interrupted.");
				}

				Thread.sleep(10);
			}
		}

		logger.debug("{}: we have {}/{} bytes ({}%) [{}/{} pieces].",
			new Object[] {
				this.getName(),
				(this.getSize() - this.left),
				this.getSize(),
				String.format("%.1f", (100f * (1f - this.left / (float)this.getSize()))),
				this.completedPieces.cardinality(),
				this.pieces.length
			});
		this.initialized = true;
	}

	private void validatePieces(List<Future<Piece>> results)
			throws IOException {
		try {
			for (Future<Piece> task : results) {
				Piece piece = task.get();
				if (this.pieces[piece.getIndex()].isValid()) {
					this.completedPieces.set(piece.getIndex());
					this.left -= piece.size();
				}
			}

			results.clear();
		} catch (Exception e) {
			throw new IOException("Error while hashing a torrent piece!", e);
		}
	}


	public synchronized void close() {
		try {
			this.bucket.close();
		} catch (IOException ioe) {
			logger.error("Error closing torrent byte storage: {}",
				ioe.getMessage());
		}
	}
	public Piece getPiece(int index) {
		if (this.pieces == null) {
			throw new IllegalStateException("Torrent not initialized yet.");
		}

		if (index >= this.pieces.length) {
			throw new IllegalArgumentException("Invalid piece index!");
		}

		return this.pieces[index];
	}
	public int getPieceCount() {
		if (this.pieces == null) {
			throw new IllegalStateException("Torrent not initialized yet.");
		}

		return this.pieces.length;
	}
	public BitSet getAvailablePieces() {
		if (!this.isInitialized()) {
			throw new IllegalStateException("Torrent not yet initialized!");
		}

		BitSet availablePieces = new BitSet(this.pieces.length);

		synchronized (this.pieces) {
			for (Piece piece : this.pieces) {
				if (piece.available()) {
					availablePieces.set(piece.getIndex());
				}
			}
		}

		return availablePieces;
	}
	public BitSet getCompletedPieces() {
		if (!this.isInitialized()) {
			throw new IllegalStateException("Torrent not yet initialized!");
		}

		synchronized (this.completedPieces) {
			return (BitSet)this.completedPieces.clone();
		}
	}

	public BitSet getRequestedPieces() {
		if (!this.isInitialized()) {
			throw new IllegalStateException("Torrent not yet initialized!");
		}

		synchronized (this.requestedPieces) {
			return (BitSet)this.requestedPieces.clone();
		}
	}

	public synchronized boolean isComplete() {
		return this.pieces.length > 0 &&
			this.completedPieces.cardinality() == this.pieces.length;
	}
	public synchronized void finish() throws IOException {
		if (!this.isInitialized()) {
			throw new IllegalStateException("Torrent not yet initialized!");
		}

		if (!this.isComplete()) {
			throw new IllegalStateException("Torrent download is not complete!");
		}

		this.bucket.finish();
	}

	public synchronized boolean isFinished() {
		return this.isComplete() && this.bucket.isFinished();
	}
	public float getCompletion() {
		return this.isInitialized()
			? (float)this.completedPieces.cardinality() /
				(float)this.pieces.length * 100.0f
			: 0.0f;
	}
	public synchronized void markCompleted(Piece piece) {
		if (this.completedPieces.get(piece.getIndex())) {
			return;
		}

		this.left -= piece.size();
		this.completedPieces.set(piece.getIndex());
	}
	@Override
	public synchronized void handlePeerChoked(SharingPeer peer) {
		Piece piece = peer.getRequestedPiece();

		if (piece != null) {
			this.requestedPieces.set(piece.getIndex(), false);
		}

		logger.trace("Peer {} choked, we now have {} outstanding " +
				"request(s): {}",
			new Object[] {
				peer,
				this.requestedPieces.cardinality(),
				this.requestedPieces
		});
	}
	@Override
	public synchronized void handlePeerReady(SharingPeer peer) {
		BitSet interesting = peer.getAvailablePieces();
		interesting.andNot(this.completedPieces);
		interesting.andNot(this.requestedPieces);

		logger.trace("Peer {} is ready and has {} interesting piece(s).",
			peer, interesting.cardinality());

		if (interesting.cardinality() == 0) {
			interesting = peer.getAvailablePieces();
			interesting.andNot(this.completedPieces);
			if (interesting.cardinality() == 0) {
				logger.trace("No interesting piece from {}!", peer);
				return;
			}

			if (this.completedPieces.cardinality() <
					ENG_GAME_COMPLETION_RATIO * this.pieces.length) {
				logger.trace("Not far along enough to warrant end-game mode.");
				return;
			}

			logger.trace("Possible end-game, we're about to request a piece " +
				"that was already requested from another peer.");
		}

		Piece chosen = requestStrategy.choosePiece(rarest, interesting, pieces);
		this.requestedPieces.set(chosen.getIndex());

		logger.trace("Requesting {} from {}, we now have {} " +
				"outstanding request(s): {}",
			new Object[] {
				chosen,
				peer,
				this.requestedPieces.cardinality(),
				this.requestedPieces
			});

		peer.downloadPiece(chosen);
	}
	@Override
	public synchronized void handlePieceAvailability(SharingPeer peer,
			Piece piece) {
		if (!this.completedPieces.get(piece.getIndex()) &&
			!this.requestedPieces.get(piece.getIndex())) {
			peer.interesting();
		}

		this.rarest.remove(piece);
		piece.seenAt(peer);
		this.rarest.add(piece);

		logger.trace("Peer {} contributes {} piece(s) [{}/{}/{}].",
			new Object[] {
				peer,
				peer.getAvailablePieces().cardinality(),
				this.completedPieces.cardinality(),
				this.getAvailablePieces().cardinality(),
				this.pieces.length
			});

		if (!peer.isChoked() &&
			peer.isInteresting() &&
			!peer.isDownloading()) {
			this.handlePeerReady(peer);
		}
	}
	@Override
	public synchronized void handleBitfieldAvailability(SharingPeer peer,
			BitSet availablePieces) {
		// Determine if the peer is interesting for us or not, and notify it.
		BitSet interesting = (BitSet)availablePieces.clone();
		interesting.andNot(this.completedPieces);
		interesting.andNot(this.requestedPieces);

		if (interesting.cardinality() == 0) {
			peer.notInteresting();
		} else {
			peer.interesting();
		}
		for (int i = availablePieces.nextSetBit(0); i >= 0;
				i = availablePieces.nextSetBit(i+1)) {
			this.rarest.remove(this.pieces[i]);
			this.pieces[i].seenAt(peer);
			this.rarest.add(this.pieces[i]);
		}

		logger.trace("Peer {} contributes {} piece(s) ({} interesting) " +
			"[completed={}; available={}/{}].",
			new Object[] {
				peer,
				availablePieces.cardinality(),
				interesting.cardinality(),
				this.completedPieces.cardinality(),
				this.getAvailablePieces().cardinality(),
				this.pieces.length
			});
	}

	@Override
	public synchronized void handlePieceSent(SharingPeer peer, Piece piece) {
		logger.trace("Completed upload of {} to {}.", piece, peer);
		this.uploaded += piece.size();
	}
	@Override
	public synchronized void handlePieceCompleted(SharingPeer peer,
		Piece piece) throws IOException {
		this.downloaded += piece.size();
		this.requestedPieces.set(piece.getIndex(), false);

		logger.trace("We now have {} piece(s) and {} outstanding request(s): {}",
			new Object[] {
				this.completedPieces.cardinality(),
				this.requestedPieces.cardinality(),
				this.requestedPieces
			});
	}
	@Override
	public synchronized void handlePeerDisconnected(SharingPeer peer) {
		BitSet availablePieces = peer.getAvailablePieces();

		for (int i = availablePieces.nextSetBit(0); i >= 0;
				i = availablePieces.nextSetBit(i+1)) {
			this.rarest.remove(this.pieces[i]);
			this.pieces[i].noLongerAt(peer);
			this.rarest.add(this.pieces[i]);
		}

		Piece requested = peer.getRequestedPiece();
		if (requested != null) {
			this.requestedPieces.set(requested.getIndex(), false);
		}

		logger.debug("Peer {} went away with {} piece(s) [completed={}; available={}/{}]",
			new Object[] {
				peer,
				availablePieces.cardinality(),
				this.completedPieces.cardinality(),
				this.getAvailablePieces().cardinality(),
				this.pieces.length
			});
		logger.trace("We now have {} piece(s) and {} outstanding request(s): {}",
			new Object[] {
				this.completedPieces.cardinality(),
				this.requestedPieces.cardinality(),
				this.requestedPieces
			});
	}

	@Override
	public synchronized void handleIOException(SharingPeer peer,
			IOException ioe) {  }
}
