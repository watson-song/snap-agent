package com.watsontech.snapagent.boot2x.routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * {@link PeerRouter} backed by a statically configured list of peer base-URLs.
 *
 * <p>Used when {@code snap-agent.routing.mode=static}. Suitable for
 * environments without service discovery where pod addresses are known ahead
 * of time. Does not auto-scale — the list must be updated on rollout.</p>
 */
public class StaticPeerRouter implements PeerRouter {

    private final List<String> peers;

    public StaticPeerRouter(List<String> staticPeers) {
        if (staticPeers == null || staticPeers.isEmpty()) {
            this.peers = Collections.emptyList();
        } else {
            this.peers = Collections.unmodifiableList(new ArrayList<String>(staticPeers));
        }
    }

    @Override
    public List<String> discoverPeers() {
        return peers;
    }

    @Override
    public String mode() {
        return "static";
    }
}
