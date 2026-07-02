package com.watsontech.snapagent.boot2x.routing;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HeadlessDnsPeerRouterTest {

    private InetAddress addr(String ip) throws UnknownHostException {
        return InetAddress.getByAddress(ip, parseIp(ip));
    }

    private byte[] parseIp(String ip) {
        String[] parts = ip.split("\\.");
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            bytes[i] = (byte) Integer.parseInt(parts[i]);
        }
        return bytes;
    }

    @Test
    void shouldNormalizeBareServiceNameLeavingAsIs() {
        // Bare service name without dots — cannot infer namespace, left as-is
        assertThat(HeadlessDnsPeerRouter.normalizeDnsName("snap-agent"))
                .isEqualTo("snap-agent");
    }

    @Test
    void shouldKeepFqdnEndingWithClusterLocal() {
        String fqdn = "snap-agent.default.svc.cluster.local";
        assertThat(HeadlessDnsPeerRouter.normalizeDnsName(fqdn)).isEqualTo(fqdn);
    }

    @Test
    void shouldKeepOtherFqdnAsIs() {
        // An FQDN that doesn't end with .svc.cluster.local is still kept (custom DNS)
        assertThat(HeadlessDnsPeerRouter.normalizeDnsName("svc.namespace.svc.mydomain"))
                .isEqualTo("svc.namespace.svc.mydomain");
    }

    @Test
    void shouldReturnEmptyWhenServiceNameNull() {
        HeadlessDnsPeerRouter router = new HeadlessDnsPeerRouter(
                null, 8080, 10, null,
                new HeadlessDnsPeerRouter.DnsResolver() {
                    @Override
                    public InetAddress[] resolveAll(String host) throws UnknownHostException {
                        return new InetAddress[0];
                    }
                });
        assertThat(router.discoverPeers()).isEmpty();
    }

    @Test
    void shouldReturnEmptyWhenPortNotConfigured() {
        HeadlessDnsPeerRouter router = new HeadlessDnsPeerRouter(
                "svc.ns.svc.cluster.local", 0, 10, null,
                new HeadlessDnsPeerRouter.DnsResolver() {
                    @Override
                    public InetAddress[] resolveAll(String host) throws UnknownHostException {
                        return new InetAddress[0];
                    }
                });
        assertThat(router.discoverPeers()).isEmpty();
    }

    @Test
    void shouldResolvePeersFromDns() throws Exception {
        HeadlessDnsPeerRouter router = new HeadlessDnsPeerRouter(
                "svc.ns.svc.cluster.local", 8080, 10, null,
                new HeadlessDnsPeerRouter.DnsResolver() {
                    @Override
                    public InetAddress[] resolveAll(String host) throws UnknownHostException {
                        return new InetAddress[]{addr("10.0.0.1"), addr("10.0.0.2")};
                    }
                });
        List<String> peers = router.discoverPeers();
        assertThat(peers).containsExactly(
                "http://10.0.0.1:8080",
                "http://10.0.0.2:8080");
    }

    @Test
    void shouldExcludeSelfPodIp() throws Exception {
        HeadlessDnsPeerRouter router = new HeadlessDnsPeerRouter(
                "svc.ns.svc.cluster.local", 8080, 10, "10.0.0.1",
                new HeadlessDnsPeerRouter.DnsResolver() {
                    @Override
                    public InetAddress[] resolveAll(String host) throws UnknownHostException {
                        return new InetAddress[]{addr("10.0.0.1"), addr("10.0.0.2"), addr("10.0.0.3")};
                    }
                });
        List<String> peers = router.discoverPeers();
        assertThat(peers).containsExactly(
                "http://10.0.0.2:8080",
                "http://10.0.0.3:8080");
    }

    @Test
    void shouldCacheResultsWithinTtl() throws Exception {
        final int[] callCount = {0};
        HeadlessDnsPeerRouter router = new HeadlessDnsPeerRouter(
                "svc.ns.svc.cluster.local", 8080, 60, null,
                new HeadlessDnsPeerRouter.DnsResolver() {
                    @Override
                    public InetAddress[] resolveAll(String host) throws UnknownHostException {
                        callCount[0]++;
                        return new InetAddress[]{addr("10.0.0.1")};
                    }
                });
        router.discoverPeers();
        router.discoverPeers();
        router.discoverPeers();
        assertThat(callCount[0]).isEqualTo(1);
    }

    @Test
    void shouldReturnEmptyOnDnsFailureAndNoCache() {
        HeadlessDnsPeerRouter router = new HeadlessDnsPeerRouter(
                "svc.ns.svc.cluster.local", 8080, 10, null,
                new HeadlessDnsPeerRouter.DnsResolver() {
                    @Override
                    public InetAddress[] resolveAll(String host) throws UnknownHostException {
                        throw new UnknownHostException("no such host");
                    }
                });
        assertThat(router.discoverPeers()).isEmpty();
    }

    @Test
    void shouldReturnStaleCacheOnFailureAfterSuccess() throws Exception {
        final int[] callCount = {0};
        HeadlessDnsPeerRouter router = new HeadlessDnsPeerRouter(
                "svc.ns.svc.cluster.local", 8080, 0, null,
                new HeadlessDnsPeerRouter.DnsResolver() {
                    @Override
                    public InetAddress[] resolveAll(String host) throws UnknownHostException {
                        callCount[0]++;
                        if (callCount[0] == 1) {
                            return new InetAddress[]{addr("10.0.0.1")};
                        }
                        throw new UnknownHostException("DNS down");
                    }
                });
        List<String> first = router.discoverPeers();
        assertThat(first).containsExactly("http://10.0.0.1:8080");
        List<String> second = router.discoverPeers();
        assertThat(second).containsExactly("http://10.0.0.1:8080");
    }

    @Test
    void shouldReturnModeHeadlessDns() {
        HeadlessDnsPeerRouter router = new HeadlessDnsPeerRouter(
                "svc", 8080, 10, null,
                new HeadlessDnsPeerRouter.DnsResolver() {
                    @Override
                    public InetAddress[] resolveAll(String host) throws UnknownHostException {
                        return new InetAddress[0];
                    }
                });
        assertThat(router.mode()).isEqualTo("headless-dns");
    }

    @Test
    void shouldIgnoreAddressesWithEmptyIp() throws Exception {
        HeadlessDnsPeerRouter router = new HeadlessDnsPeerRouter(
                "svc.ns.svc.cluster.local", 8080, 10, null,
                new HeadlessDnsPeerRouter.DnsResolver() {
                    @Override
                    public InetAddress[] resolveAll(String host) throws UnknownHostException {
                        return new InetAddress[]{addr("10.0.0.2")};
                    }
                });
        List<String> peers = router.discoverPeers();
        assertThat(peers).hasSize(1);
    }
}
