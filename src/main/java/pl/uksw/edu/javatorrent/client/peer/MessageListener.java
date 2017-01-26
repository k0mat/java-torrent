
package pl.uksw.edu.javatorrent.client.peer;

import pl.uksw.edu.javatorrent.common.protocol.PeerMessage;

import java.util.EventListener;

public interface MessageListener extends EventListener {

	public void handleMessage(PeerMessage msg);
}
