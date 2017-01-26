
package pl.uksw.edu.javatorrent.common;

import pl.uksw.edu.javatorrent.bcodec.BDecoder;
import pl.uksw.edu.javatorrent.bcodec.BEValue;
import pl.uksw.edu.javatorrent.bcodec.BEncoder;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;

public class Torrent {

	private static final Logger logger =
		LoggerFactory.getLogger(Torrent.class);
	public static final int DEFAULT_PIECE_LENGTH = 512 * 1024;
	public static final int PIECE_HASH_SIZE = 20;
	public static final String BYTE_ENCODING = "ISO-8859-1";
	public static class TorrentFile {

		public final File file;
		public final long size;

		public TorrentFile(File file, long size) {
			this.file = file;
			this.size = size;
		}
	}


	protected final byte[] encoded;
	protected final byte[] encoded_info;
	protected final Map<String, BEValue> decoded;
	protected final Map<String, BEValue> decoded_info;

	private final byte[] info_hash;
	private final String hex_info_hash;

	private final List<List<URI>> trackers;
	private final Set<URI> allTrackers;
	private final Date creationDate;
	private final String comment;
	private final String createdBy;
	private final String name;
	private final long size;
	private final int pieceLength;

	protected final List<TorrentFile> files;

	private final boolean seeder;
	public Torrent(byte[] torrent, boolean seeder) throws IOException, NoSuchAlgorithmException {
		this.encoded = torrent;
		this.seeder = seeder;

		this.decoded = BDecoder.bdecode(
				new ByteArrayInputStream(this.encoded)).getMap();

		this.decoded_info = this.decoded.get("info").getMap();
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		BEncoder.bencode(this.decoded_info, baos);
		this.encoded_info = baos.toByteArray();
		this.info_hash = Torrent.hash(this.encoded_info);
		this.hex_info_hash = Utils.bytesToHex(this.info_hash);
		try {
			this.trackers = new ArrayList<List<URI>>();
			this.allTrackers = new HashSet<URI>();

			if (this.decoded.containsKey("announce-list")) {
				List<BEValue> tiers = this.decoded.get("announce-list").getList();
				for (BEValue tv : tiers) {
					List<BEValue> trackers = tv.getList();
					if (trackers.isEmpty()) {
						continue;
					}

					List<URI> tier = new ArrayList<URI>();
					for (BEValue tracker : trackers) {
						URI uri = new URI(tracker.getString());
						if (!this.allTrackers.contains(uri)) {
							tier.add(uri);
							this.allTrackers.add(uri);
						}
					}
					if (!tier.isEmpty()) {
						this.trackers.add(tier);
					}
				}
			} else if (this.decoded.containsKey("announce")) {
				URI tracker = new URI(this.decoded.get("announce").getString());
				this.allTrackers.add(tracker);
				List<URI> tier = new ArrayList<URI>();
				tier.add(tracker);
				this.trackers.add(tier);
			}
		} catch (URISyntaxException use) {
			throw new IOException(use);
		}

		this.creationDate = this.decoded.containsKey("creation date")
			? new Date(this.decoded.get("creation date").getLong() * 1000)
			: null;
		this.comment = this.decoded.containsKey("comment")
			? this.decoded.get("comment").getString()
			: null;
		this.createdBy = this.decoded.containsKey("created by")
			? this.decoded.get("created by").getString()
			: null;
		this.name = this.decoded_info.get("name").getString();
		this.pieceLength = this.decoded_info.get("piece length").getInt();

		this.files = new LinkedList<TorrentFile>();
		if (this.decoded_info.containsKey("files")) {
			for (BEValue file : this.decoded_info.get("files").getList()) {
				Map<String, BEValue> fileInfo = file.getMap();
				StringBuilder path = new StringBuilder();
				for (BEValue pathElement : fileInfo.get("path").getList()) {
					path.append(File.separator)
						.append(pathElement.getString());
				}
				this.files.add(new TorrentFile(
					new File(this.name, path.toString()),
					fileInfo.get("length").getLong()));
			}
		} else {
			this.files.add(new TorrentFile(
				new File(this.name),
				this.decoded_info.get("length").getLong()));
		}
		long size = 0;
		for (TorrentFile file : this.files) {
			size += file.size;
		}
		this.size = size;

		logger.info("{}-file torrent information:",
			this.isMultifile() ? "Multi" : "Single");
		logger.info("  Torrent name: {}", this.name);
		logger.info("  Announced at:" + (this.trackers.size() == 0 ? " Seems to be trackerless" : ""));
		for (int i=0; i < this.trackers.size(); i++) {
			List<URI> tier = this.trackers.get(i);
			for (int j=0; j < tier.size(); j++) {
				logger.info("    {}{}",
					(j == 0 ? String.format("%2d. ", i+1) : "    "),
					tier.get(j));
			}
		}

		if (this.creationDate != null) {
			logger.info("  Created on..: {}", this.creationDate);
		}
		if (this.comment != null) {
			logger.info("  Comment.....: {}", this.comment);
		}
		if (this.createdBy != null) {
			logger.info("  Created by..: {}", this.createdBy);
		}

		if (this.isMultifile()) {
			logger.info("  Found {} file(s) in multi-file torrent structure.",
				this.files.size());
			int i = 0;
			for (TorrentFile file : this.files) {
				logger.debug("    {}. {} ({} byte(s))",
					new Object[] {
						String.format("%2d", ++i),
						file.file.getPath(),
						String.format("%,d", file.size)
					});
			}
		}

		logger.info("  Pieces......: {} piece(s) ({} byte(s)/piece)",
			(this.size / this.decoded_info.get("piece length").getInt()) + 1,
			this.decoded_info.get("piece length").getInt());
		logger.info("  Total size..: {} byte(s)",
			String.format("%,d", this.size));
	}
	public String getName() {
		return this.name;
	}

	public String getComment() {
		return this.comment;
	}

	public String getCreatedBy() {
		return this.createdBy;
	}

	public long getSize() {
		return this.size;
	}

	public List<String> getFilenames() {
		List<String> filenames = new LinkedList<String>();
		for (TorrentFile file : this.files) {
			filenames.add(file.file.getPath());
		}
		return filenames;
	}


	public boolean isMultifile() {
		return this.files.size() > 1;
	}


	public byte[] getInfoHash() {
		return this.info_hash;
	}

	public String getHexInfoHash() {
		return this.hex_info_hash;
	}

	public String toString() {
		return this.getName();
	}

	public byte[] getEncoded() {
		return this.encoded;
	}

	public List<List<URI>> getAnnounceList() {
		return this.trackers;
	}

	public int getTrackerCount() {
		return this.allTrackers.size();
	}

	public boolean isSeeder() {
		return this.seeder;
	}

	public void save(OutputStream output) throws IOException {
		output.write(this.getEncoded());
	}

	public static byte[] hash(byte[] data) throws NoSuchAlgorithmException {
		MessageDigest crypt;
		crypt = MessageDigest.getInstance("SHA-1");
		crypt.reset();
		crypt.update(data);
		return crypt.digest();
	}

	public static String toHexString(String input) {
		try {
			byte[] bytes = input.getBytes(Torrent.BYTE_ENCODING);
			return Utils.bytesToHex(bytes);
		} catch (UnsupportedEncodingException uee) {
			return null;
		}
	}

	protected static int getHashingThreadsCount() {
		String threads = System.getenv("TTORRENT_HASHING_THREADS");

		if (threads != null) {
			try {
				int count = Integer.parseInt(threads);
				if (count > 0) {
					return count;
				}
			} catch (NumberFormatException nfe) {
				// Pass
			}
		}

		return Runtime.getRuntime().availableProcessors();
	}

	public static Torrent load(File torrent) throws IOException, NoSuchAlgorithmException {
		return Torrent.load(torrent, false);
	}

	public static Torrent load(File torrent, boolean seeder)
		throws IOException, NoSuchAlgorithmException {
		byte[] data = FileUtils.readFileToByteArray(torrent);
		return new Torrent(data, seeder);
	}

	public static Torrent create(File source, URI announce, String createdBy)
		throws InterruptedException, IOException, NoSuchAlgorithmException {
		return Torrent.create(source, null, DEFAULT_PIECE_LENGTH, 
				announce, null, createdBy);
	}

	public static Torrent create(File parent, List<File> files, URI announce,
		String createdBy) throws InterruptedException, IOException, NoSuchAlgorithmException {
		return Torrent.create(parent, files, DEFAULT_PIECE_LENGTH, 
				announce, null, createdBy);
	}

	public static Torrent create(File source, int pieceLength, List<List<URI>> announceList,
			String createdBy) throws InterruptedException, IOException, NoSuchAlgorithmException {
		return Torrent.create(source, null, pieceLength, 
				null, announceList, createdBy);
	}

	public static Torrent create(File source, List<File> files, int pieceLength,
			List<List<URI>> announceList, String createdBy)
			throws InterruptedException, IOException, NoSuchAlgorithmException {
		return Torrent.create(source, files, pieceLength, 
				null, announceList, createdBy);
	}

	private static Torrent create(File parent, List<File> files, int pieceLength,
				URI announce, List<List<URI>> announceList, String createdBy)
			throws InterruptedException, IOException, NoSuchAlgorithmException {
		if (files == null || files.isEmpty()) {
			logger.info("Creating single-file torrent for {}...",
				parent.getName());
		} else {
			logger.info("Creating {}-file torrent {}...",
				files.size(), parent.getName());
		}

		Map<String, BEValue> torrent = new HashMap<String, BEValue>();

		if (announce != null) {
			torrent.put("announce", new BEValue(announce.toString()));
		}
		if (announceList != null) {
			List<BEValue> tiers = new LinkedList<BEValue>();
			for (List<URI> trackers : announceList) {
				List<BEValue> tierInfo = new LinkedList<BEValue>();
				for (URI trackerURI : trackers) {
					tierInfo.add(new BEValue(trackerURI.toString()));
				}
				tiers.add(new BEValue(tierInfo));
			}
			torrent.put("announce-list", new BEValue(tiers));
		}
		
		torrent.put("creation date", new BEValue(new Date().getTime() / 1000));
		torrent.put("created by", new BEValue(createdBy));

		Map<String, BEValue> info = new TreeMap<String, BEValue>();
		info.put("name", new BEValue(parent.getName()));
		info.put("piece length", new BEValue(pieceLength));

		if (files == null || files.isEmpty()) {
			info.put("length", new BEValue(parent.length()));
			info.put("pieces", new BEValue(Torrent.hashFile(parent, pieceLength),
				Torrent.BYTE_ENCODING));
		} else {
			List<BEValue> fileInfo = new LinkedList<BEValue>();
			for (File file : files) {
				Map<String, BEValue> fileMap = new HashMap<String, BEValue>();
				fileMap.put("length", new BEValue(file.length()));

				LinkedList<BEValue> filePath = new LinkedList<BEValue>();
				while (file != null) {
					if (file.equals(parent)) {
						break;
					}

					filePath.addFirst(new BEValue(file.getName()));
					file = file.getParentFile();
				}

				fileMap.put("path", new BEValue(filePath));
				fileInfo.add(new BEValue(fileMap));
			}
			info.put("files", new BEValue(fileInfo));
			info.put("pieces", new BEValue(Torrent.hashFiles(files, pieceLength),
				Torrent.BYTE_ENCODING));
		}
		torrent.put("info", new BEValue(info));

		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		BEncoder.bencode(new BEValue(torrent), baos);
		return new Torrent(baos.toByteArray(), true);
	}
	private static class CallableChunkHasher implements Callable<String> {

		private final MessageDigest md;
		private final ByteBuffer data;

		CallableChunkHasher(ByteBuffer buffer) throws NoSuchAlgorithmException {
			this.md = MessageDigest.getInstance("SHA-1");

			this.data = ByteBuffer.allocate(buffer.remaining());
			buffer.mark();
			this.data.put(buffer);
			this.data.clear();
			buffer.reset();
		}

		@Override
		public String call() throws UnsupportedEncodingException {
			this.md.reset();
			this.md.update(this.data.array());
			return new String(md.digest(), Torrent.BYTE_ENCODING);
		}
	}

	private static String hashFile(File file, int pieceLenght)
		throws InterruptedException, IOException, NoSuchAlgorithmException {
		return Torrent.hashFiles(Arrays.asList(new File[] { file }), pieceLenght);
	}

	private static String hashFiles(List<File> files, int pieceLenght)
		throws InterruptedException, IOException, NoSuchAlgorithmException {
		int threads = getHashingThreadsCount();
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		ByteBuffer buffer = ByteBuffer.allocate(pieceLenght);
		List<Future<String>> results = new LinkedList<Future<String>>();
		StringBuilder hashes = new StringBuilder();

		long length = 0L;
		int pieces = 0;

		long start = System.nanoTime();
		for (File file : files) {
			logger.info("Hashing data from {} with {} threads ({} pieces)...",
				new Object[] {
					file.getName(),
					threads,
					(int) (Math.ceil(
						(double)file.length() / pieceLenght))
				});

			length += file.length();

			FileInputStream fis = new FileInputStream(file);
			FileChannel channel = fis.getChannel();
			int step = 10;

			try {
				while (channel.read(buffer) > 0) {
					if (buffer.remaining() == 0) {
						buffer.clear();
						results.add(executor.submit(new CallableChunkHasher(buffer)));
					}

					if (results.size() >= threads) {
						pieces += accumulateHashes(hashes, results);
					}

					if (channel.position() / (double)channel.size() * 100f > step) {
						logger.info("  ... {}% complete", step);
						step += 10;
					}
				}
			} finally {
				channel.close();
				fis.close();
			}
		}


		if (buffer.position() > 0) {
			buffer.limit(buffer.position());
			buffer.position(0);
			results.add(executor.submit(new CallableChunkHasher(buffer)));
		}

		pieces += accumulateHashes(hashes, results);
		executor.shutdown();
		while (!executor.isTerminated()) {
			Thread.sleep(10);
		}
		long elapsed = System.nanoTime() - start;

		int expectedPieces = (int) (Math.ceil(
				(double)length / pieceLenght));
		logger.info("Hashed {} file(s) ({} bytes) in {} pieces ({} expected) in {}ms.",
			new Object[] {
				files.size(),
				length,
				pieces,
				expectedPieces,
				String.format("%.1f", elapsed/1e6),
			});

		return hashes.toString();
	}
	private static int accumulateHashes(StringBuilder hashes,
			List<Future<String>> results) throws InterruptedException, IOException {
		try {
			int pieces = results.size();
			for (Future<String> chunk : results) {
				hashes.append(chunk.get());
			}
			results.clear();
			return pieces;
		} catch (ExecutionException ee) {
			throw new IOException("Error while hashing the torrent data!", ee);
		}
	}
}
