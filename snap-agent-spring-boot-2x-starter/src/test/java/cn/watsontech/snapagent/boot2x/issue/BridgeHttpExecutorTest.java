package cn.watsontech.snapagent.boot2x.issue;

import cn.watsontech.snapagent.core.issue.HttpResponse;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link BridgeHttpExecutor}.
 *
 * <p>Tests status-first routing logic: when bridge is active → proxyRequest,
 * when inactive → delegate to direct executor.</p>
 */
class BridgeHttpExecutorTest {

    private static class CapturingDirectExecutor implements cn.watsontech.snapagent.core.issue.HttpExecutor {
        int callCount = 0;
        String lastUrl;
        String lastMethod;

        @Override
        public HttpResponse execute(String url, String method,
                                    Map<String, String> headers, Object body) {
            callCount++;
            lastUrl = url;
            lastMethod = method;
            return new HttpResponse(200, "direct-response", null);
        }
    }

    private static class CapturingBridgeService extends IssueBridgeService {
        int proxyCallCount = 0;
        String lastServiceType;

        CapturingBridgeService() {
            super(5000, Collections.singletonList("*"));
        }

        @Override
        public HttpResponse proxyRequest(String serviceType, String url, String method,
                                         Map<String, String> headers, Object body) {
            proxyCallCount++;
            lastServiceType = serviceType;
            return new HttpResponse(200, "bridge-response", null);
        }

        @Override
        public boolean isBridgeActive(String serviceType) {
            // Active when master is enabled and service is enabled
            return super.isBridgeActive(serviceType);
        }

        void setBridgeActive(String serviceType, boolean active) {
            Map<String, Boolean> services = new HashMap<>();
            services.put(serviceType, active);
            updateClientStatus(new BridgeClientStatus(true, true, services));
        }
    }

    @Test
    void execute_shouldRouteToDirectWhenBridgeInactive() {
        CapturingDirectExecutor direct = new CapturingDirectExecutor();
        CapturingBridgeService bridge = new CapturingBridgeService();
        BridgeHttpExecutor executor = new BridgeHttpExecutor(direct, bridge, "issue-tracker");

        // Bridge not active (extension not installed)
        HttpResponse resp = executor.execute("http://api.example.com/issues",
                "GET", null, null);

        assertThat(direct.callCount).isEqualTo(1);
        assertThat(bridge.proxyCallCount).isEqualTo(0);
        assertThat(resp.getBody()).isEqualTo("direct-response");
    }

    @Test
    void execute_shouldRouteToBridgeWhenActive() {
        CapturingDirectExecutor direct = new CapturingDirectExecutor();
        CapturingBridgeService bridge = new CapturingBridgeService();
        bridge.setBridgeActive("issue-tracker", true);
        BridgeHttpExecutor executor = new BridgeHttpExecutor(direct, bridge, "issue-tracker");

        HttpResponse resp = executor.execute("http://api.example.com/issues",
                "GET", null, null);

        assertThat(direct.callCount).isEqualTo(0);
        assertThat(bridge.proxyCallCount).isEqualTo(1);
        assertThat(bridge.lastServiceType).isEqualTo("issue-tracker");
        assertThat(resp.getBody()).isEqualTo("bridge-response");
    }

    @Test
    void execute_shouldRouteToDirectWhenServiceNotEnabled() {
        CapturingDirectExecutor direct = new CapturingDirectExecutor();
        CapturingBridgeService bridge = new CapturingBridgeService();
        // Extension installed + master on, but issue-tracker service is OFF
        bridge.setBridgeActive("issue-tracker", false);
        // vcs is enabled but we're using issue-tracker executor
        bridge.setBridgeActive("vcs", true);
        BridgeHttpExecutor executor = new BridgeHttpExecutor(direct, bridge, "issue-tracker");

        HttpResponse resp = executor.execute("http://api.example.com/issues",
                "GET", null, null);

        assertThat(direct.callCount).isEqualTo(1);
        assertThat(bridge.proxyCallCount).isEqualTo(0);
    }

    @Test
    void execute_shouldRouteToDirectWhenMasterSwitchOff() {
        CapturingDirectExecutor direct = new CapturingDirectExecutor();
        CapturingBridgeService bridge = new CapturingBridgeService();
        // Installed, service enabled, but master off
        Map<String, Boolean> services = new HashMap<>();
        services.put("issue-tracker", true);
        bridge.updateClientStatus(new BridgeClientStatus(true, false, services));
        BridgeHttpExecutor executor = new BridgeHttpExecutor(direct, bridge, "issue-tracker");

        HttpResponse resp = executor.execute("http://api.example.com/issues",
                "GET", null, null);

        assertThat(direct.callCount).isEqualTo(1);
        assertThat(bridge.proxyCallCount).isEqualTo(0);
    }

    @Test
    void execute_shouldRouteToDirectWhenExtensionNotInstalled() {
        CapturingDirectExecutor direct = new CapturingDirectExecutor();
        CapturingBridgeService bridge = new CapturingBridgeService();
        // Extension not installed
        bridge.updateClientStatus(BridgeClientStatus.inactive());
        BridgeHttpExecutor executor = new BridgeHttpExecutor(direct, bridge, "issue-tracker");

        HttpResponse resp = executor.execute("http://api.example.com/issues",
                "GET", null, null);

        assertThat(direct.callCount).isEqualTo(1);
        assertThat(bridge.proxyCallCount).isEqualTo(0);
    }

    @Test
    void getServiceType_shouldReturnConfiguredTag() {
        BridgeHttpExecutor executor = new BridgeHttpExecutor(
                new CapturingDirectExecutor(), new CapturingBridgeService(), "vcs");
        assertThat(executor.getServiceType()).isEqualTo("vcs");
    }

    @Test
    void execute_shouldPassThroughMethodAndUrlToDirect() {
        CapturingDirectExecutor direct = new CapturingDirectExecutor();
        CapturingBridgeService bridge = new CapturingBridgeService();
        BridgeHttpExecutor executor = new BridgeHttpExecutor(direct, bridge, "issue-tracker");

        executor.execute("http://api.example.com:8080/issues/123", "POST",
                Collections.singletonMap("Authorization", "Token abc"), "{\"title\":\"test\"}");

        assertThat(direct.lastUrl).isEqualTo("http://api.example.com:8080/issues/123");
        assertThat(direct.lastMethod).isEqualTo("POST");
    }
}
