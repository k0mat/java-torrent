
package pl.uksw.edu.javatorrent.client.peer;

import pl.uksw.edu.javatorrent.client.Piece;

import java.io.IOException;
import java.util.BitSet;
import java.util.EventListener;


public interface PeerActivityListener extends EventListener {


	public void handlePeerChoked(SharingPeer peer);
	public void handlePeerReady(SharingPeer peer);
	public void handlePieceAvailability(SharingPeer peer, Piece piece);
	public void handleBitfieldAvailability(SharingPeer peer, BitSet availablePieces);
	public void handlePieceSent(SharingPeer peer, Piece piece);
	public void handlePieceCompleted(SharingPeer peer, Piece piece)
		throws IOException;
	public void handlePeerDisconnected(SharingPeer peer);
	public void handleIOException(SharingPeer peer, IOException ioe);
}
