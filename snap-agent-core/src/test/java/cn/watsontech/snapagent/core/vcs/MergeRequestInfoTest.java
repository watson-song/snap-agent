package cn.watsontech.snapagent.core.vcs;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MergeRequestInfoTest {

    @Test
    void constructor_setsAllFields() {
        MergeRequestInfo mr = new MergeRequestInfo("https://gitlab/mr/1", "1", "abc123");
        assertThat(mr.getPrUrl()).isEqualTo("https://gitlab/mr/1");
        assertThat(mr.getPrNumber()).isEqualTo("1");
        assertThat(mr.getCommitSha()).isEqualTo("abc123");
    }

    @Test
    void toString_containsAllFields() {
        MergeRequestInfo mr = new MergeRequestInfo("url", "42", "sha");
        assertThat(mr.toString()).contains("url").contains("42").contains("sha");
    }
}
