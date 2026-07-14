package cn.watsontech.snapagent.boot2x.routing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * {@link PeerRouter} that discovers sibling pods by resolving a headless
 * Service DNS name via the cluster DNS.
 *
 * <p>For a headless Service ({@code clusterIP: None}), the cluster DNS returns
 * one A record per ready pod, so a single {@link InetAddress#getAllByName}
 * call enumerates all peers. No SA token or RBAC is required — only DNS.</p>
 *
 * <p>Used when {@code snap-agent.routing.mode=headless-dns}. The full DNS
 * name is typically {@code {service}.{namespace}.svc.cluster.local}; configure
 * via {@code snap-agent.routing.k8s-service-name} as either the full FQDN or
 * just the service name (in which case {@code .svc.cluster.local} is appended).
 * Each peer URL is the peer pod's server root ({@code http://{ip}:{port}});
 * the {@link PeerSseRelay} appends the configured {@code internal-path}. The
 * pod's own IP (from {@code MY_POD_IP} env var) is excluded.</p>
 */
public class HeadlessDnsPeerRouter implements PeerRouter {

    private static final Logger log = LoggerFactory.getLogger(HeadlessDnsPeerRouter.class);
    private static final String SELF_POD_IP_ENV = "MY_POD_IP";
    private static final String CLUSTER_LOCAL_SUFFIX = ".svc.cluster.local";

    private final String dnsName;
    private final int port;
    private final String selfPodIp;
    private final int cacheTtlSeconds;
    private final DnsResolver dnsResolver;

    private volatile List<String> cachedPeers = null;
    private volatile long cacheExpiryMillis = 0L;

    /** Production constructor — uses JDK DNS and {@code MY_POD_IP} env var. */
    public HeadlessDnsPeerRouter(String serviceName, int port, int cacheTtlSeconds) {
        this(serviceName, port, cacheTtlSeconds,
                System.getenv(SELF_POD_IP_ENV), new JdkDnsResolver());
    }

    /** Testable constructor — allows injecting a fake DNS resolver and self-IP. */
    public HeadlessDnsPeerRouter(String serviceName, int port, int cacheTtlSeconds,
                                 String selfPodIp, DnsResolver dnsResolver) {
        this.dnsName = normalizeDnsName(serviceName);
        this.port = port;
        this.cacheTtlSeconds = cacheTtlSeconds;
        this.selfPodIp = selfPodIp;
        this.dnsResolver = dnsResolver;
    }

    @Override
    public List<String> discoverPeers() {
        if (cachedPeers != null && System.currentTimeMillis() < cacheExpiryMillis) {
            return cachedPeers;
        }
        if (dnsName == null || dnsName.isEmpty()) {
            log.warn("HeadlessDnsPeerRouter: k8s-service-name not configured");
            return Collections.emptyList();
        }
        if (port <= 0) {
            log.warn("HeadlessDnsPeerRouter: port not configured");
            return Collections.emptyList();
        }
        try {
            List<String> fresh = resolvePeers();
            cachedPeers = fresh;
            cacheExpiryMillis = System.currentTimeMillis()
                    + Math.max(0, cacheTtlSeconds) * 1000L;
            return fresh;
        } catch (Exception e) {
            log.warn("HeadlessDnsPeerRouter: DNS resolution failed: {}", e.getMessage());
            if (cachedPeers != null) {
                log.info("HeadlessDnsPeerRouter: serving stale cached peer list");
                return cachedPeers;
            }
            return Collections.emptyList();
        }
    }

    @Override
    public String mode() {
        return "headless-dns";
    }

    private List<String> resolvePeers() throws UnknownHostException {
        InetAddress[] addrs = dnsResolver.resolveAll(dnsName);
        List<String> peers = new ArrayList<String>();
        for (InetAddress addr : addrs) {
            String ip = addr.getHostAddress();
            if (ip == null || ip.isEmpty()) {
                continue;
            }
            if (ip.equals(selfPodIp)) {
                continue; // exclude self
            }
            peers.add("http://" + ip + ":" + port);
        }
        return peers;
    }

    static String normalizeDnsName(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        if (raw.contains(".") && !raw.endsWith(CLUSTER_LOCAL_SUFFIX)) {
            return raw;
        }
        if (raw.endsWith(CLUSTER_LOCAL_SUFFIX)) {
            return raw;
        }
        return raw;
    }

    /** SPI for DNS resolution so tests can inject fake responses. */
    public interface DnsResolver {
        InetAddress[] resolveAll(String host) throws UnknownHostException;
    }

    static class JdkDnsResolver implements DnsResolver {
        @Override
        public InetAddress[] resolveAll(String host) throws UnknownHostException {
            return InetAddress.getAllByName(host);
        }
    }
}
