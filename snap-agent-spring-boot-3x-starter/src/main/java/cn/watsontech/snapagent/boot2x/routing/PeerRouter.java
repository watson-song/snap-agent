package cn.watsontech.snapagent.boot2x.routing;

import java.util.List;

/**
 * SPI for discovering sibling pod base-URLs in a multi-instance deployment.
 *
 * <p>Implementations enumerate peer pods so that a {@code GET /runs/{id}/stream}
 * request landing on the wrong pod can probe peers for the task owner and relay
 * the SSE stream. No shared state is required — discovery is read-only.</p>
 *
 * <p>Implementations: {@link K8sApiPeerRouter}, {@link HeadlessDnsPeerRouter},
 * {@link StaticPeerRouter}. Selected via {@code snap-agent.routing.mode}.</p>
 */
public interface PeerRouter {

    /**
     * Returns the base-URLs of all reachable sibling pods (excluding self).
     *
     * @return unmodifiable list of peer base-URLs (e.g.
     *         {@code http://10.0.1.5:8080}); empty if no peers or discovery fails
     */
    List<String> discoverPeers();

    /** The discovery mode name (e.g. "k8s-api", "headless-dns", "static"). */
    String mode();
}
