
package pl.uksw.edu.javatorrent.client;

import pl.uksw.edu.javatorrent.client.peer.SharingPeer;

import java.nio.channels.SocketChannel;
import java.util.EventListener;

public interface IncomingConnectionListener extends EventListener {

	public void handleNewPeerConnection(SocketChannel channel, byte[] peerId);

	public void handleFailedConnection(SharingPeer peer, Throwable cause);
}
