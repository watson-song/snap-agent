package com.watsontech.snapagent.boot2x.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * {@link PeerRouter} that discovers sibling pods by querying the in-cluster
 * Kubernetes Endpoints API.
 *
 * <p>Requires the pod's service account to have {@code get} on the
 * {@code endpoints} resource for {@code snap-agent.routing.k8s-service-name}.
 * The SA token is read from the standard mount path
 * {@code /var/run/secrets/kubernetes.io/serviceaccount/token}; the namespace
 * from the sibling {@code namespace} file. The pod's own IP is taken from the
 * {@code MY_POD_IP} env var (K8s downward API) and excluded from the result.</p>
 *
 * <p>Used when {@code snap-agent.routing.mode=k8s-api}. Each peer URL is the
 * peer pod's server root ({@code http://{ip}:{port}}); the {@link PeerSseRelay}
 * appends the configured {@code internal-path} to reach the pod-to-pod
 * endpoints. Discovery results are cached for
 * {@code snap-agent.routing.discovery-cache-ttl-seconds}; on failure the last
 * good result is returned (stale-OK) so that transient API errors do not break
 * cross-pod relay.</p>
 */
public class K8sApiPeerRouter implements PeerRouter {

    private static final Logger log = LoggerFactory.getLogger(K8sApiPeerRouter.class);

    private static final String DEFAULT_API_HOST = "https://kubernetes.default.svc";
    private static final String SA_TOKEN_PATH = "/var/run/secrets/kubernetes.io/serviceaccount/token";
    private static final String SA_NAMESPACE_PATH = "/var/run/secrets/kubernetes.io/serviceaccount/namespace";
    private static final String SELF_POD_IP_ENV = "MY_POD_IP";

    private final String serviceName;
    private final int port;
    private final String apiHost;
    private final String namespace;
    private final String saToken;
    private final String selfPodIp;
    private final int cacheTtlSeconds;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    private volatile List<String> cachedPeers = null;
    private volatile long cacheExpiryMillis = 0L;

    /**
     * Production constructor — reads SA token + namespace from the standard
     * in-cluster mount paths.
     */
    public K8sApiPeerRouter(String serviceName, int port, int cacheTtlSeconds) {
        this(serviceName, port, cacheTtlSeconds,
                DEFAULT_API_HOST,
                readNamespace(),
                readSaToken(),
                System.getenv(SELF_POD_IP_ENV));
    }

    /**
     * Testable constructor — allows injecting namespace, token, self-IP, and
     * API host so unit tests do not need a real cluster.
     */
    public K8sApiPeerRouter(String serviceName, int port, int cacheTtlSeconds,
                            String apiHost, String namespace, String saToken,
                            String selfPodIp) {
        this(serviceName, port, cacheTtlSeconds, apiHost, namespace, saToken,
                selfPodIp, new OkHttpClient.Builder()
                        .connectTimeout(3, TimeUnit.SECONDS)
                        .readTimeout(5, TimeUnit.SECONDS)
                        .build());
    }

    /** Testable constructor that also allows injecting a custom HTTP client. */
    public K8sApiPeerRouter(String serviceName, int port, int cacheTtlSeconds,
                            String apiHost, String namespace, String saToken,
                            String selfPodIp, OkHttpClient httpClient) {
        this.serviceName = serviceName;
        this.port = port;
        this.cacheTtlSeconds = cacheTtlSeconds;
        this.apiHost = apiHost != null ? apiHost : DEFAULT_API_HOST;
        this.namespace = namespace;
        this.saToken = saToken;
        this.selfPodIp = selfPodIp;
        this.objectMapper = new ObjectMapper();
        this.httpClient = httpClient;
    }

    @Override
    public List<String> discoverPeers() {
        if (cachedPeers != null && System.currentTimeMillis() < cacheExpiryMillis) {
            return cachedPeers;
        }
        if (serviceName == null || serviceName.isEmpty()) {
            log.warn("K8sApiPeerRouter: k8s-service-name not configured; returning empty");
            return Collections.emptyList();
        }
        if (saToken == null || saToken.isEmpty()) {
            log.warn("K8sApiPeerRouter: SA token not available; returning empty");
            return Collections.emptyList();
        }
        if (namespace == null || namespace.isEmpty()) {
            log.warn("K8sApiPeerRouter: namespace not available; returning empty");
            return Collections.emptyList();
        }
        try {
            List<String> fresh = fetchPeersFromApi();
            cachedPeers = fresh;
            cacheExpiryMillis = System.currentTimeMillis()
                    + Math.max(0, cacheTtlSeconds) * 1000L;
            return fresh;
        } catch (Exception e) {
            log.warn("K8sApiPeerRouter: discovery failed: {}", e.getMessage());
            if (cachedPeers != null) {
                log.info("K8sApiPeerRouter: serving stale cached peer list");
                return cachedPeers;
            }
            return Collections.emptyList();
        }
    }

    @Override
    public String mode() {
        return "k8s-api";
    }

    private List<String> fetchPeersFromApi() throws IOException {
        String url = apiHost + "/api/v1/namespaces/" + namespace
                + "/endpoints/" + serviceName;
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + saToken)
                .header("Accept", "application/json")
                .build();

        List<String> peers = new ArrayList<String>();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("endpoints API returned HTTP " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("endpoints API returned empty body");
            }
            String json = body.string();
            peers.addAll(parseEndpoints(json));
        }
        return peers;
    }

    /**
     * Parses the Endpoints JSON: walks {@code .subsets[].addresses[].ip} and
     * {@code .subsets[].ports[]} to build {@code http://{ip}:{port}} server-root
     * URLs. Excludes the pod's own IP when {@code MY_POD_IP} is set.
     */
    List<String> parseEndpoints(String json) throws IOException {
        List<String> result = new ArrayList<String>();
        JsonNode root = objectMapper.readTree(json);
        JsonNode subsets = root.get("subsets");
        if (subsets == null || !subsets.isArray()) {
            return result;
        }
        for (JsonNode subset : subsets) {
            int endpointPort = selectPort(subset);
            JsonNode addresses = subset.get("addresses");
            if (addresses != null && addresses.isArray()) {
                for (JsonNode addr : addresses) {
                    addPeerIfPresent(result, addr, endpointPort);
                }
            }
            JsonNode notReady = subset.get("notReadyAddresses");
            if (notReady != null && notReady.isArray()) {
                for (JsonNode addr : notReady) {
                    addPeerIfPresent(result, addr, endpointPort);
                }
            }
        }
        return result;
    }

    private void addPeerIfPresent(List<String> result, JsonNode addr, int endpointPort) {
        JsonNode ipNode = addr.get("ip");
        if (ipNode == null || ipNode.asText().isEmpty()) {
            return;
        }
        String ip = ipNode.asText();
        if (ip.equals(selfPodIp)) {
            return; // exclude self
        }
        int p = port > 0 ? port : endpointPort;
        if (p <= 0) {
            return;
        }
        result.add("http://" + ip + ":" + p);
    }

    private int selectPort(JsonNode subset) {
        JsonNode ports = subset.get("ports");
        if (ports == null || !ports.isArray() || ports.size() == 0) {
            return 0;
        }
        for (JsonNode p : ports) {
            JsonNode name = p.get("name");
            if (name != null && "http".equals(name.asText())) {
                JsonNode portNode = p.get("port");
                return portNode != null ? portNode.asInt() : 0;
            }
        }
        JsonNode portNode = ports.get(0).get("port");
        return portNode != null ? portNode.asInt() : 0;
    }

    private static String readNamespace() {
        return readFileOrNull(Paths.get(SA_NAMESPACE_PATH));
    }

    private static String readSaToken() {
        return readFileOrNull(Paths.get(SA_TOKEN_PATH));
    }

    private static String readFileOrNull(Path path) {
        try {
            if (!Files.exists(path)) {
                return null;
            }
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return null;
        }
    }
}
