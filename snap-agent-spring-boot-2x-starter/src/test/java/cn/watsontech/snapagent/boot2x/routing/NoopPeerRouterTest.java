package cn.watsontech.snapagent.boot2x.routing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NoopPeerRouterTest {

    @Test
    void shouldReturnEmptyList() {
        NoopPeerRouter router = new NoopPeerRouter();
        assertThat(router.discoverPeers()).isEmpty();
    }

    @Test
    void shouldReturnModeNone() {
        NoopPeerRouter router = new NoopPeerRouter();
        assertThat(router.mode()).isEqualTo("none");
    }
}
