package cn.watsontech.snapagent.boot2x.routing;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class K8sApiPeerRouterTest {

    private static final String ENDPOINTS_JSON =
            "{\n" +
            "  \"kind\": \"Endpoints\",\n" +
            "  \"subsets\": [\n" +
            "    {\n" +
            "      \"addresses\": [\n" +
            "        {\"ip\": \"10.0.0.1\"},\n" +
            "        {\"ip\": \"10.0.0.2\"}\n" +
            "      ],\n" +
            "      \"ports\": [\n" +
            "        {\"name\": \"http\", \"port\": 8080, \"protocol\": \"TCP\"}\n" +
            "      ]\n" +
            "    }\n" +
            "  ]\n" +
            "}";

    private static final String ENDPOINTS_WITH_NOT_READY =
            "{\n" +
            "  \"subsets\": [\n" +
            "    {\n" +
            "      \"addresses\": [{\"ip\": \"10.0.0.1\"}],\n" +
            "      \"notReadyAddresses\": [{\"ip\": \"10.0.0.5\"}],\n" +
            "      \"ports\": [{\"name\": \"http\", \"port\": 8080}]\n" +
            "    }\n" +
            "  ]\n" +
            "}";

    private static final String ENDPOINTS_MULTIPLE_PORTS =
            "{\n" +
            "  \"subsets\": [\n" +
            "    {\n" +
            "      \"addresses\": [{\"ip\": \"10.0.0.1\"}],\n" +
            "      \"ports\": [\n" +
            "        {\"name\": \"metrics\", \"port\": 9090},\n" +
            "        {\"name\": \"http\", \"port\": 8080}\n" +
            "      ]\n" +
            "    }\n" +
            "  ]\n" +
            "}";

    // ---- parseEndpoints tests ----

    @Test
    void shouldParseStandardEndpoints() throws Exception {
        K8sApiPeerRouter router = newRouter("svc", 0, null);
        List<String> peers = router.parseEndpoints(ENDPOINTS_JSON);
        assertThat(peers).containsExactly(
                "http://10.0.0.1:8080",
                "http://10.0.0.2:8080");
    }

    @Test
    void shouldParseNotReadyAddresses() throws Exception {
        K8sApiPeerRouter router = newRouter("svc", 0, null);
        List<String> peers = router.parseEndpoints(ENDPOINTS_WITH_NOT_READY);
        assertThat(peers).containsExactly(
                "http://10.0.0.1:8080",
                "http://10.0.0.5:8080");
    }

    @Test
    void shouldPreferHttpNamedPort() throws Exception {
        K8sApiPeerRouter router = newRouter("svc", 0, null);
        List<String> peers = router.parseEndpoints(ENDPOINTS_MULTIPLE_PORTS);
        assertThat(peers).containsExactly("http://10.0.0.1:8080");
    }

    @Test
    void shouldOverrideEndpointPortWithConfiguredPort() throws Exception {
        K8sApiPeerRouter router = newRouter("svc", 9999, null);
        List<String> peers = router.parseEndpoints(ENDPOINTS_JSON);
        assertThat(peers).containsExactly(
                "http://10.0.0.1:9999",
                "http://10.0.0.2:9999");
    }

    @Test
    void shouldExcludeSelfPodIp() throws Exception {
        K8sApiPeerRouter router = newRouter("svc", 0, "10.0.0.1");
        List<String> peers = router.parseEndpoints(ENDPOINTS_JSON);
        assertThat(peers).containsExactly("http://10.0.0.2:8080");
    }

    @Test
    void shouldReturnEmptyWhenNoSubsets() throws Exception {
        K8sApiPeerRouter router = newRouter("svc", 0, null);
        List<String> peers = router.parseEndpoints("{\"kind\":\"Endpoints\"}");
        assertThat(peers).isEmpty();
    }

    @Test
    void shouldReturnEmptyWhenSubsetsMissingAddresses() throws Exception {
        K8sApiPeerRouter router = newRouter("svc", 0, null);
        List<String> peers = router.parseEndpoints(
                "{\"subsets\":[{\"ports\":[{\"port\":8080}]}]}");
        assertThat(peers).isEmpty();
    }

    @Test
    void shouldSkipAddressesWithEmptyIp() throws Exception {
        K8sApiPeerRouter router = newRouter("svc", 0, null);
        List<String> peers = router.parseEndpoints(
                "{\"subsets\":[{\"addresses\":[{\"ip\":\"\"},{\"ip\":\"10.0.0.9\"}]," +
                "\"ports\":[{\"port\":8080}]}]}");
        assertThat(peers).containsExactly("http://10.0.0.9:8080");
    }

    // ---- discoverPeers tests ----

    @Test
    void shouldReturnEmptyWhenServiceNameMissing() {
        K8sApiPeerRouter router = new K8sApiPeerRouter(
                "", 8080, 10,
                "https://k8s", "default", "token", null);
        assertThat(router.discoverPeers()).isEmpty();
    }

    @Test
    void shouldReturnEmptyWhenSaTokenMissing() {
        K8sApiPeerRouter router = new K8sApiPeerRouter(
                "svc", 8080, 10,
                "https://k8s", "default", "", null);
        assertThat(router.discoverPeers()).isEmpty();
    }

    @Test
    void shouldReturnEmptyWhenNamespaceMissing() {
        K8sApiPeerRouter router = new K8sApiPeerRouter(
                "svc", 8080, 10,
                "https://k8s", "", "token", null);
        assertThat(router.discoverPeers()).isEmpty();
    }

    @Test
    void shouldDiscoverPeersViaApi() {
        OkHttpClient client = singleResponseClient(200, ENDPOINTS_JSON);
        K8sApiPeerRouter router = new K8sApiPeerRouter(
                "svc", 8080, 60,
                "https://k8s.default.svc", "default", "token", "0.0.0.0",
                client);
        List<String> peers = router.discoverPeers();
        assertThat(peers).containsExactly(
                "http://10.0.0.1:8080",
                "http://10.0.0.2:8080");
    }

    @Test
    void shouldCacheResultsWithinTtl() {
        final int[] callCount = {0};
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    callCount[0]++;
                    return singleResponse(200, ENDPOINTS_JSON, chain);
                })
                .build();
        K8sApiPeerRouter router = new K8sApiPeerRouter(
                "svc", 8080, 60,
                "https://k8s.default.svc", "default", "token", null,
                client);
        router.discoverPeers();
        router.discoverPeers();
        router.discoverPeers();
        assertThat(callCount[0]).isEqualTo(1);
    }

    @Test
    void shouldReturnEmptyWhenApiReturnsErrorAndNoCache() {
        OkHttpClient client = singleResponseClient(500, "{}");
        K8sApiPeerRouter router = new K8sApiPeerRouter(
                "svc", 8080, 60,
                "https://k8s.default.svc", "default", "token", null,
                client);
        assertThat(router.discoverPeers()).isEmpty();
    }

    @Test
    void shouldReturnStaleCacheOnApiFailure() {
        final int[] callCount = {0};
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    callCount[0]++;
                    if (callCount[0] == 1) {
                        return singleResponse(200, ENDPOINTS_JSON, chain);
                    }
                    return singleResponse(500, "{}", chain);
                })
                .build();
        K8sApiPeerRouter router = new K8sApiPeerRouter(
                "svc", 8080, 0,
                "https://k8s.default.svc", "default", "token", null,
                client);
        List<String> first = router.discoverPeers();
        assertThat(first).hasSize(2);
        List<String> second = router.discoverPeers();
        assertThat(second).hasSize(2);
        assertThat(second).isEqualTo(first);
    }

    @Test
    void shouldReturnModeK8sApi() {
        K8sApiPeerRouter router = newRouter("svc", 8080, null);
        assertThat(router.mode()).isEqualTo("k8s-api");
    }

    // ---- helpers ----

    private K8sApiPeerRouter newRouter(String svc, int port, String selfIp) {
        return new K8sApiPeerRouter(svc, port, 10,
                "https://k8s.default.svc", "default", "token", selfIp);
    }

    private static okhttp3.Response singleResponse(int code, String body, Interceptor.Chain chain) {
        return new okhttp3.Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("test")
                .body(ResponseBody.create(okhttp3.MediaType.parse("application/json"), body))
                .build();
    }

    private static OkHttpClient singleResponseClient(int code, String body) {
        return new OkHttpClient.Builder()
                .addInterceptor(chain -> singleResponse(code, body, chain))
                .build();
    }
}
