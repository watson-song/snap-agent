package com.watsontech.snapagent.boot2x.routing;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StaticPeerRouterTest {

    @Test
    void shouldReturnConfiguredPeers() {
        StaticPeerRouter router = new StaticPeerRouter(Arrays.asList(
                "http://10.0.0.1:8080/snap-agent",
                "http://10.0.0.2:8080/snap-agent"));

        List<String> peers = router.discoverPeers();
        assertThat(peers).hasSize(2);
        assertThat(peers.get(0)).isEqualTo("http://10.0.0.1:8080/snap-agent");
    }

    @Test
    void shouldReturnEmptyListWhenInputNull() {
        StaticPeerRouter router = new StaticPeerRouter(null);
        assertThat(router.discoverPeers()).isEmpty();
    }

    @Test
    void shouldReturnEmptyListWhenInputEmpty() {
        StaticPeerRouter router = new StaticPeerRouter(Collections.<String>emptyList());
        assertThat(router.discoverPeers()).isEmpty();
    }

    @Test
    void shouldReturnModeStatic() {
        StaticPeerRouter router = new StaticPeerRouter(Collections.<String>emptyList());
        assertThat(router.mode()).isEqualTo("static");
    }

    @Test
    void shouldReturnImmutableList() {
        StaticPeerRouter router = new StaticPeerRouter(
                new java.util.ArrayList<String>(Arrays.asList("http://peer:8080/snap-agent")));
        List<String> peers = router.discoverPeers();
        // mutating the source list must not affect the router's view
        try {
            peers.add("http://injected:8080/snap-agent");
        } catch (UnsupportedOperationException expected) {
            // expected — unmodifiable
        }
        assertThat(router.discoverPeers()).hasSize(1);
    }
}
