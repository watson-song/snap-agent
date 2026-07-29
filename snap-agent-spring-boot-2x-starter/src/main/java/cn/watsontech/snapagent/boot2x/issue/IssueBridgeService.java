package cn.watsontech.snapagent.boot2x.issue;

import cn.watsontech.snapagent.core.issue.HttpResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Core bridge service: manages SSE emitters, pending proxy requests, and
 * the browser extension's status.
 *
 * <p>{@link BridgeHttpExecutor} calls {@link #isBridgeActive(String)} to
 * decide whether to route through the bridge or use direct connection.
 * When routing through the bridge, it calls {@link #proxyRequest} which
 * pushes an SSE event to all connected frontends and blocks on a
 * {@link CompletableFuture} until the result arrives via
 * {@link #handleResult}.</p>
 */
public class IssueBridgeService {

    private static final Logger log = LoggerFactory.getLogger(IssueBridgeService.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final long requestTimeoutMs;
    private final List<String> allowedHostPatterns;

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<SseEmitter>();
    private final Map<String, CompletableFuture<HttpResponse>> pending =
            new ConcurrentHashMap<String, CompletableFuture<HttpResponse>>();

    private volatile BridgeClientStatus clientStatus = BridgeClientStatus.inactive();

    public IssueBridgeService(long requestTimeoutMs, List<String> allowedHostPatterns) {
        this.requestTimeoutMs = requestTimeoutMs;
        this.allowedHostPatterns = allowedHostPatterns;
    }

    // ---- extension status tracking ----

    /**
     * Called when the frontend reports the extension's status (installed,
     * master switch, per-service toggles).
     */
    public void updateClientStatus(BridgeClientStatus status) {
        this.clientStatus = status;
        log.info("Bridge client status updated: installed={}, masterEnabled={}, services={}",
                 status.isInstalled(), status.isMasterEnabled(), status.getServices());
    }

    /**
     * Returns {@code true} if the extension is installed, the master switch
     * is on, and the given service type's proxy is enabled.
     */
    public boolean isBridgeActive(String serviceType) {
        BridgeClientStatus status = this.clientStatus;
        return status.isInstalled()
            && status.isMasterEnabled()
            && status.getServices().getOrDefault(serviceType, false);
    }

    // ---- SSE emitter management ----

    /**
     * Registers a new SSE emitter for a frontend bridge client.
     */
    public SseEmitter registerEmitter() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> {
            emitters.remove(emitter);
            log.debug("SSE emitter completed; {} remaining", emitters.size());
        });
        emitter.onTimeout(() -> {
            emitters.remove(emitter);
            log.debug("SSE emitter timed out; {} remaining", emitters.size());
        });
        return emitter;
    }

    // ---- proxy request lifecycle ----

    /**
     * Pushes a proxy-request SSE event to all connected frontends and blocks
     * until the result arrives or the timeout expires.
     *
     * @throws AbstractHttpIssueTracker.TrackerException if no client is
     *         connected, the request times out, or the frontend returns an error
     */
    public HttpResponse proxyRequest(String serviceType, String url, String method,
                                     Map<String, String> headers, Object body) {
        validateUrl(url);

        String requestId = UUID.randomUUID().toString();
        CompletableFuture<HttpResponse> future = new CompletableFuture<HttpResponse>();
        pending.put(requestId, future);

        BridgeRequest req = new BridgeRequest(requestId, serviceType, url, method, headers, body);
        String jsonData;
        try {
            jsonData = objectMapper.writeValueAsString(req);
        } catch (Exception e) {
            pending.remove(requestId);
            throw new AbstractHttpIssueTracker.TrackerException("bridge",
                    "Failed to serialize bridge request: " + e.getMessage(), e);
        }

        if (emitters.isEmpty()) {
            pending.remove(requestId);
            throw new AbstractHttpIssueTracker.TrackerException("bridge",
                    "No bridge client connected; cannot proxy request to " + url);
        }

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .id(requestId)
                        .name("proxy-request")
                        .data(jsonData));
            } catch (Exception e) {
                log.warn("Failed to send SSE to emitter: {}", e.getMessage());
                emitters.remove(emitter);
            }
        }

        try {
            return future.get(requestTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new AbstractHttpIssueTracker.TrackerException("bridge",
                    "Bridge request timed out after " + requestTimeoutMs + "ms for " + url);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AbstractHttpIssueTracker.TrackerException("bridge",
                    "Bridge request interrupted for " + url, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof AbstractHttpIssueTracker.TrackerException) {
                throw (AbstractHttpIssueTracker.TrackerException) cause;
            }
            throw new AbstractHttpIssueTracker.TrackerException("bridge",
                    "Bridge request failed for " + url + ": "
                            + (cause != null ? cause.getMessage() : e.getMessage()), cause);
        } finally {
            pending.remove(requestId);
        }
    }

    /**
     * Receives the result from the frontend and completes the pending future.
     */
    public void handleResult(BridgeResponse response) {
        CompletableFuture<HttpResponse> future = pending.get(response.getId());
        if (future == null) {
            log.warn("Received bridge result for unknown request: {}", response.getId());
            return;
        }
        if (response.getError() != null) {
            future.completeExceptionally(
                    new AbstractHttpIssueTracker.TrackerException("bridge", response.getError()));
        } else {
            future.complete(new HttpResponse(response.getStatus(), response.getBody(),
                    response.getHeaders()));
        }
    }

    // ---- status query ----

    public BridgeStatus getStatus() {
        BridgeClientStatus cs = this.clientStatus;
        return new BridgeStatus(
                !emitters.isEmpty(),
                emitters.size(),
                pending.size(),
                cs.isInstalled(),
                cs.isMasterEnabled(),
                cs.getServices()
        );
    }

    // ---- security ----

    void validateUrl(String url) {
        String host = URI.create(url).getHost();
        if (host == null) {
            throw new AbstractHttpIssueTracker.TrackerException("bridge",
                    "Invalid URL (no host): " + url);
        }
        if (allowedHostPatterns != null) {
            for (String pattern : allowedHostPatterns) {
                if (matchHost(host, pattern)) {
                    return;
                }
            }
        }
        throw new AbstractHttpIssueTracker.TrackerException("bridge",
                "URL not allowed by bridge host patterns: " + url);
    }

    static boolean matchHost(String host, String pattern) {
        if (pattern.startsWith("*.")) {
            String suffix = pattern.substring(2);
            return host.endsWith(suffix) && host.length() > suffix.length();
        }
        return host.equalsIgnoreCase(pattern);
    }
}
