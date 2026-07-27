package cn.watsontech.snapagent.core.vcs;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FixResultTest {

    @Test
    void success_setsAllFields() {
        List<String> files = Arrays.asList("src/A.java", "src/B.java");
        FixResult result = FixResult.success("sha", "url", "1", files);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getCommitId()).isEqualTo("sha");
        assertThat(result.getPrUrl()).isEqualTo("url");
        assertThat(result.getPrNumber()).isEqualTo("1");
        assertThat(result.getChangedFiles()).containsExactly("src/A.java", "src/B.java");
        assertThat(result.getErrorMessage()).isNull();
    }

    @Test
    void failed_setsErrorMessage() {
        FixResult result = FixResult.failed("AI produced no changes");
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getCommitId()).isNull();
        assertThat(result.getPrUrl()).isNull();
        assertThat(result.getPrNumber()).isNull();
        assertThat(result.getChangedFiles()).isEmpty();
        assertThat(result.getErrorMessage()).isEqualTo("AI produced no changes");
    }

    @Test
    void changedFiles_isDefensivelyCopied() {
        List<String> files = new java.util.ArrayList<String>(Arrays.asList("src/A.java"));
        FixResult result = FixResult.success("sha", "url", "1", files);
        files.add("src/B.java");
        assertThat(result.getChangedFiles()).hasSize(1).containsExactly("src/A.java");
    }

    @Test
    void changedFiles_isUnmodifiable() {
        FixResult result = FixResult.success("sha", "url", "1", Arrays.asList("f"));
        java.util.List<String> list = result.getChangedFiles();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> list.add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
