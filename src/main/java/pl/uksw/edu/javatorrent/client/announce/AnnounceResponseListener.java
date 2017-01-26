
package pl.uksw.edu.javatorrent.client.announce;

import pl.uksw.edu.javatorrent.common.Peer;

import java.util.EventListener;
import java.util.List;

public interface AnnounceResponseListener extends EventListener {

	public void handleAnnounceResponse(int interval, int complete,
                                       int incomplete);

	public void handleDiscoveredPeers(List<Peer> peers);
}
