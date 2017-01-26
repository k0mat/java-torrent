
package pl.uksw.edu.javatorrent.client.announce;

import pl.uksw.edu.javatorrent.client.SharedTorrent;
import pl.uksw.edu.javatorrent.common.Peer;
import pl.uksw.edu.javatorrent.common.protocol.TrackerMessage.*;
import pl.uksw.edu.javatorrent.common.protocol.http.*;
import org.apache.commons.io.output.ByteArrayOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;

public class HTTPTrackerClient extends TrackerClient {

	protected static final Logger logger =
		LoggerFactory.getLogger(HTTPTrackerClient.class);

	protected HTTPTrackerClient(SharedTorrent torrent, Peer peer,
		URI tracker) {
		super(torrent, peer, tracker);
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

		URL target = null;
		try {
			HTTPAnnounceRequestMessage request =
				this.buildAnnounceRequest(event);
			target = request.buildAnnounceURL(this.tracker.toURL());
		} catch (MalformedURLException mue) {
			throw new AnnounceException("Invalid announce URL (" +
				mue.getMessage() + ")", mue);
		} catch (MessageValidationException mve) {
			throw new AnnounceException("Announce request creation violated " +
				"expected protocol (" + mve.getMessage() + ")", mve);
		} catch (IOException ioe) {
			throw new AnnounceException("Error building announce request (" +
				ioe.getMessage() + ")", ioe);
		}

		HttpURLConnection conn = null;
		InputStream in = null;
		try {
			conn = (HttpURLConnection)target.openConnection();
			in = conn.getInputStream();
		} catch (IOException ioe) {
			if (conn != null) {
				in = conn.getErrorStream();
			}
		}

		if (in == null) {
			throw new AnnounceException("No response or unreachable tracker!");
		}

		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			baos.write(in);


			HTTPTrackerMessage message =
				HTTPTrackerMessage.parse(ByteBuffer.wrap(baos.toByteArray()));
			this.handleTrackerAnnounceResponse(message, inhibitEvents);
		} catch (IOException ioe) {
			throw new AnnounceException("Error reading tracker response!", ioe);
		} catch (MessageValidationException mve) {
			throw new AnnounceException("Tracker message violates expected " +
				"protocol (" + mve.getMessage() + ")", mve);
		} finally {

			try {
				in.close();
			} catch (IOException ioe) {
				logger.warn("Problem ensuring error stream closed!", ioe);
			}


			InputStream err = conn.getErrorStream();
			if (err != null) {
				try {
					err.close();
				} catch (IOException ioe) {
					logger.warn("Problem ensuring error stream closed!", ioe);
				}
			}
		}
	}

	private HTTPAnnounceRequestMessage buildAnnounceRequest(
		AnnounceRequestMessage.RequestEvent event)
		throws UnsupportedEncodingException, IOException,
			MessageValidationException {
		// Build announce request message
		return HTTPAnnounceRequestMessage.craft(
				this.torrent.getInfoHash(),
				this.peer.getPeerId().array(),
				this.peer.getPort(),
				this.torrent.getUploaded(),
				this.torrent.getDownloaded(),
				this.torrent.getLeft(),
				true, false, event,
				this.peer.getIp(),
				AnnounceRequestMessage.DEFAULT_NUM_WANT);
	}
}
