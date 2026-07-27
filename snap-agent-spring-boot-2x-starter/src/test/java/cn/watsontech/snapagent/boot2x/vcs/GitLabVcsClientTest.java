package cn.watsontech.snapagent.boot2x.vcs;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import cn.watsontech.snapagent.core.vcs.FileChange;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitLabVcsClientTest {

    private GitLabVcsClient createClient() {
        SnapAgentProperties.Vcs.GitLab cfg = new SnapAgentProperties.Vcs.GitLab();
        cfg.setBaseUrl("http://localhost:39999");
        cfg.setToken("glpat-test");
        cfg.setProjectId(123);
        return new GitLabVcsClient(cfg);
    }

    @Test
    void shouldReturnTypeGitlab() {
        assertThat(createClient().type()).isEqualTo("gitlab");
    }

    @Test
    void shouldThrowWhenServerUnreachable() {
        assertThatThrownBy(() -> createClient().createBranch("fix/test"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed POST");
    }

    @Test
    void shouldHandleBaseUrlWithTrailingSlash() {
        SnapAgentProperties.Vcs.GitLab cfg = new SnapAgentProperties.Vcs.GitLab();
        cfg.setBaseUrl("https://gitlab.example.com/");
        cfg.setToken("tok");
        cfg.setProjectId(1);
        GitLabVcsClient client = new GitLabVcsClient(cfg);
        assertThat(client.type()).isEqualTo("gitlab");
    }

    @Test
    void shouldHandleCommitFilesEmpty() {
        GitLabVcsClient client = createClient();
        assertThatThrownBy(() -> client.commitFiles("fix/test",
                new ArrayList<FileChange>(), "msg"))
                .isInstanceOf(RuntimeException.class);
    }
}
