package cn.watsontech.snapagent.boot2x.routing;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Probes sibling pods for ownership of a task and relays their SSE stream
 * back to the client through the local {@link SseEmitter}.
 *
 * <p>When a {@code GET /runs/{id}/stream} request lands on a pod that does not
 * own the task, this component asks each peer (in order) via the internal
 * probe endpoint. The first peer that returns HTTP 200 is the owner; the relay
 * then opens its internal stream endpoint and pipes events to the client.</p>
 *
 * <p>Authentication is a single shared secret ({@code X-Skills-Agent-Internal-Token}
 * header) configured via {@code snap-agent.routing.internal-token}. If the
 * token is empty, relay is disabled (cross-pod routing is effectively off
 * even if a {@link PeerRouter} returns peers).</p>
 */
public class PeerSseRelay {

    private static final Logger log = LoggerFactory.getLogger(PeerSseRelay.class);
    public static final String INTERNAL_TOKEN_HEADER = "X-Skills-Agent-Internal-Token";

    private final PeerRouter peerRouter;
    private final String internalToken;
    private final String internalPath;
    private final OkHttpClient httpClient;

    public PeerSseRelay(PeerRouter peerRouter, String internalToken, String internalPath) {
        this.peerRouter = peerRouter;
        this.internalToken = internalToken;
        this.internalPath = internalPath;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.MINUTES) // long-lived stream
                .build();
    }

    /** Testable constructor with a custom HTTP client. */
    public PeerSseRelay(PeerRouter peerRouter, String internalToken, String internalPath,
                        OkHttpClient httpClient) {
        this.peerRouter = peerRouter;
        this.internalToken = internalToken;
        this.internalPath = internalPath;
        this.httpClient = httpClient;
    }

    /**
     * Attempts to relay the SSE stream for {@code taskId} from a peer pod.
     *
     * <p><b>Blocking</b> — must be called from a worker thread. Probes peers
     * sequentially; on the first hit, opens the stream and pipes events to
     * {@code emitter} until the peer completes or errors. Returns {@code true}
     * if a peer was found and the stream was relayed (or relayed then ended);
     * {@code false} if no peer had the task or relay is disabled.</p>
     */
    public boolean tryRelay(SseEmitter emitter, String taskId) {
        if (internalToken == null || internalToken.isEmpty()) {
            log.debug("PeerSseRelay: internal-token not set; relay disabled");
            return false;
        }
        List<String> peers = peerRouter.discoverPeers();
        if (peers == null || peers.isEmpty()) {
            log.debug("PeerSseRelay: no peers discovered (mode={})", peerRouter.mode());
            return false;
        }
        for (String peerBaseUrl : peers) {
            String owner = probePeer(peerBaseUrl, taskId);
            if (owner == null) {
                continue; // peer unreachable or 404 — try next
            }
            log.info("PeerSseRelay: task {} found on peer {}", taskId, owner);
            try {
                relayStream(emitter, owner, taskId);
                return true;
            } catch (Exception e) {
                log.warn("PeerSseRelay: relay from {} failed mid-stream: {}",
                        owner, e.getMessage());
                // continue to next peer — task may have moved (rare) or peer crashed
            }
        }
        log.info("PeerSseRelay: no peer owns task {}", taskId);
        return false;
    }

    /**
     * Probes a single peer for task ownership. Returns the peer's base URL if
     * the peer responds 200 to the probe endpoint, or {@code null} if the
     * peer is unreachable, responds 401/404, or any other non-200 status.
     */
    private String probePeer(String peerBaseUrl, String taskId) {
        String url = peerBaseUrl + internalPath + "/tasks/" + taskId + "/probe";
        Request request = new Request.Builder()
                .url(url)
                .header(INTERNAL_TOKEN_HEADER, internalToken)
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            int code = response.code();
            if (code == 200) {
                return peerBaseUrl;
            }
            if (code == 404) {
                log.debug("PeerSseRelay: peer {} does not own task {}", peerBaseUrl, taskId);
                return null;
            }
            if (code == 401) {
                log.warn("PeerSseRelay: peer {} rejected internal token", peerBaseUrl);
                return null;
            }
            log.warn("PeerSseRelay: peer {} probe returned HTTP {}", peerBaseUrl, code);
            return null;
        } catch (IOException e) {
            log.debug("PeerSseRelay: peer {} unreachable: {}", peerBaseUrl, e.getMessage());
            return null;
        }
    }

    /**
     * Opens the peer's internal stream endpoint and pipes SSE events to the
     * local emitter. Blocks until the peer completes or the connection drops.
     */
    private void relayStream(SseEmitter emitter, String peerBaseUrl, String taskId)
            throws IOException {
        String url = peerBaseUrl + internalPath + "/tasks/" + taskId + "/stream";
        Request request = new Request.Builder()
                .url(url)
                .header(INTERNAL_TOKEN_HEADER, internalToken)
                .header("Accept", "text/event-stream")
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("peer stream endpoint returned HTTP " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("peer stream endpoint returned empty body");
            }
            pipeSse(emitter, body.byteStream());
        }
    }

    /**
     * Reads the peer's SSE byte stream line by line, re-assembles events, and
     * re-emits them via the local {@link SseEmitter}.
     *
     * <p>SSE framing: {@code event: <name>}, {@code data: <payload>}, blank
     * line dispatches. Multi-line {@code data:} is concatenated with newlines.
     * Comment lines ({@code :...}) are ignored.</p>
     */
    void pipeSse(SseEmitter emitter, InputStream in) throws IOException {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8));
        String eventName = null;
        StringBuilder dataBuf = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                if (eventName != null || dataBuf.length() > 0) {
                    SseEmitter.SseEventBuilder event = SseEmitter.event();
                    if (eventName != null) {
                        event.name(eventName);
                    }
                    if (dataBuf.length() > 0) {
                        event.data(dataBuf.toString());
                    }
                    try {
                        emitter.send(event);
                    } catch (Exception e) {
                        // client gone — stop piping
                        log.debug("PeerSseRelay: client disconnected: {}", e.getMessage());
                        return;
                    }
                }
                eventName = null;
                dataBuf.setLength(0);
                continue;
            }
            if (line.startsWith("event:")) {
                eventName = line.substring(6).trim();
            } else if (line.startsWith("data:")) {
                if (dataBuf.length() > 0) {
                    dataBuf.append('\n');
                }
                dataBuf.append(line.substring(5).trim());
            } else if (line.startsWith(":")) {
                // comment/heartbeat — ignore
            }
            // unknown lines ignored
        }
        // stream ended — complete the local emitter
        try {
            emitter.complete();
        } catch (Exception e) {
            log.debug("PeerSseRelay: complete after stream end failed: {}", e.getMessage());
        }
    }
}
