
package pl.uksw.edu.javatorrent.common;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

public class Peer {

	private final InetSocketAddress address;
	private final String hostId;

	private ByteBuffer peerId;
	private String hexPeerId;

	public Peer(InetSocketAddress address) {
		this(address, null);
	}


	public Peer(String ip, int port) {
		this(new InetSocketAddress(ip, port), null);
	}

	public Peer(String ip, int port, ByteBuffer peerId) {
		this(new InetSocketAddress(ip, port), peerId);
	}

	public Peer(InetSocketAddress address, ByteBuffer peerId) {
		this.address = address;
		this.hostId = String.format("%s:%d",
			this.address.getAddress(),
			this.address.getPort());

		this.setPeerId(peerId);
	}

	public boolean hasPeerId() {
		return this.peerId != null;
	}


	public ByteBuffer getPeerId() {
		return this.peerId;
	}

	public void setPeerId(ByteBuffer peerId) {
		if (peerId != null) {
			this.peerId = peerId;
			this.hexPeerId = Utils.bytesToHex(peerId.array());
		} else {
			this.peerId = null;
			this.hexPeerId = null;
		}
	}


	public String getHexPeerId() {
		return this.hexPeerId;
	}


	public String getShortHexPeerId() {
		return String.format("..%s",
			this.hexPeerId.substring(this.hexPeerId.length()-6).toUpperCase());
	}
	public String getIp() {
		return this.address.getAddress().getHostAddress();
	}
	public InetAddress getAddress() {
		return this.address.getAddress();
	}
	public int getPort() {
		return this.address.getPort();
	}
	public String getHostIdentifier() {
		return this.hostId;
	}
	public byte[] getRawIp() {
		return this.address.getAddress().getAddress();
	}
	public String toString() {
		StringBuilder s = new StringBuilder("peer://")
			.append(this.getIp()).append(":").append(this.getPort())
			.append("/");

		if (this.hasPeerId()) {
			s.append(this.hexPeerId.substring(this.hexPeerId.length()-6));
		} else {
			s.append("?");
		}

		if (this.getPort() < 10000) {
			s.append(" ");
		}

		return s.toString();
	}

	public boolean looksLike(Peer other) {
		if (other == null) {
			return false;
		}

		return this.hostId.equals(other.hostId) &&
			(this.hasPeerId()
				 ? this.hexPeerId.equals(other.hexPeerId)
				 : true);
	}
}
