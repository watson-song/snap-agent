package cn.watsontech.snapagent.boot2x.vcs;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BitbucketVcsClientTest {

    private BitbucketVcsClient createClient() {
        SnapAgentProperties.Vcs.Bitbucket cfg = new SnapAgentProperties.Vcs.Bitbucket();
        cfg.setBaseUrl("http://localhost:39999");
        cfg.setToken("bb-test");
        cfg.setProjectKey("PROJ");
        cfg.setRepoSlug("myrepo");
        return new BitbucketVcsClient(cfg);
    }

    @Test
    void shouldReturnTypeBitbucket() {
        assertThat(createClient().type()).isEqualTo("bitbucket");
    }

    @Test
    void shouldThrowWhenServerUnreachable() {
        assertThatThrownBy(() -> createClient().createBranch("fix/test"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed POST");
    }

    @Test
    void shouldHandleBaseUrlWithTrailingSlash() {
        SnapAgentProperties.Vcs.Bitbucket cfg = new SnapAgentProperties.Vcs.Bitbucket();
        cfg.setBaseUrl("https://bitbucket.example.com/");
        cfg.setToken("tok");
        cfg.setProjectKey("PK");
        cfg.setRepoSlug("repo");
        BitbucketVcsClient client = new BitbucketVcsClient(cfg);
        assertThat(client.type()).isEqualTo("bitbucket");
    }
}
