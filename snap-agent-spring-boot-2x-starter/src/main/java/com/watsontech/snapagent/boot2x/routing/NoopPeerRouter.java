package com.watsontech.snapagent.boot2x.routing;

import java.util.Collections;
import java.util.List;

/**
 * Fallback {@link PeerRouter} that returns an empty peer list.
 *
 * <p>Used when {@code snap-agent.routing.mode=none} (default) — the stream
 * endpoint falls back to local-only behaviour and, if the task is not found
 * locally, returns a {@code task_not_found} SSE event.</p>
 */
public class NoopPeerRouter implements PeerRouter {

    @Override
    public List<String> discoverPeers() {
        return Collections.emptyList();
    }

    @Override
    public String mode() {
        return "none";
    }
}
