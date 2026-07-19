package cn.watsontech.snapagent.boot2x.patrol;

import cn.watsontech.snapagent.core.agent.TranscriptEvent;
import cn.watsontech.snapagent.core.patrol.BugfixSuggestion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateBugfixSuggesterTest {

    private final TemplateBugfixSuggester suggester = new TemplateBugfixSuggester();

    private Map<String, Object> argsWithFilePath(String filePath) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("file_path", filePath);
        return m;
    }

    @Test
    @DisplayName("extracts file paths from code_read tool calls and commits from git_log results")
    void shouldExtractFilesAndCommits() {
        List<TranscriptEvent> transcript = Arrays.asList(
            TranscriptEvent.toolCall("call-1", "code_read", argsWithFilePath("/src/OrderService.java")),
            TranscriptEvent.toolCall("call-2", "git_log", argsWithFilePath("/src/OrderService.java")),
            TranscriptEvent.toolResult("call-1", 1, false, 100, "public class OrderService { ... }", null),
            TranscriptEvent.toolResult("call-2", 1, false, 100, "abc1234 Fix NPE in OrderService\ndef5678 Refactor", null),
            TranscriptEvent.done("SUCCEEDED", "The root cause is a null pointer in OrderService.createOrder at line 87.")
        );

        BugfixSuggestion result = suggester.suggest("task-1", transcript);

        assertThat(result.getTaskId()).isEqualTo("task-1");
        assertThat(result.getRootCause()).contains("null pointer");
        assertThat(result.getAffectedFiles()).contains("/src/OrderService.java");
        assertThat(result.getCommitRefs()).contains("abc1234");
        assertThat(result.getConfidence()).isEqualTo(BugfixSuggestion.CONFIDENCE_HIGH);
    }

    @Test
    @DisplayName("confidence MEDIUM when only code_read used (no git_log)")
    void shouldReturnMediumConfidenceWhenOnlyCodeRead() {
        List<TranscriptEvent> transcript = Arrays.asList(
            TranscriptEvent.toolCall("call-1", "code_read", argsWithFilePath("/src/Foo.java")),
            TranscriptEvent.toolResult("call-1", 1, false, 100, "content", null),
            TranscriptEvent.done("SUCCEEDED", "Some root cause")
        );

        BugfixSuggestion result = suggester.suggest("task-1", transcript);
        assertThat(result.getConfidence()).isEqualTo(BugfixSuggestion.CONFIDENCE_MEDIUM);
        assertThat(result.getAffectedFiles()).contains("/src/Foo.java");
        assertThat(result.getCommitRefs()).isEmpty();
    }

    @Test
    @DisplayName("confidence LOW when no code_read or git_log used")
    void shouldReturnLowConfidenceWhenNoCodeTools() {
        List<TranscriptEvent> transcript = Collections.singletonList(
            TranscriptEvent.done("SUCCEEDED", "Just a text response")
        );

        BugfixSuggestion result = suggester.suggest("task-1", transcript);
        assertThat(result.getConfidence()).isEqualTo(BugfixSuggestion.CONFIDENCE_LOW);
        assertThat(result.getAffectedFiles()).isEmpty();
        assertThat(result.getCommitRefs()).isEmpty();
    }

    @Test
    @DisplayName("empty transcript returns empty suggestion with LOW confidence")
    void shouldHandleEmptyTranscript() {
        BugfixSuggestion result = suggester.suggest("task-1", Collections.emptyList());
        assertThat(result.getConfidence()).isEqualTo(BugfixSuggestion.CONFIDENCE_LOW);
        assertThat(result.getRootCause()).isNull();
        assertThat(result.getSuggestion()).contains("No diagnostic data available");
    }

    @Test
    @DisplayName("suggestion text includes root cause, files, and commits")
    void shouldGenerateSuggestionText() {
        List<TranscriptEvent> transcript = Arrays.asList(
            TranscriptEvent.toolCall("call-1", "code_read", argsWithFilePath("/src/Bar.java")),
            TranscriptEvent.toolCall("call-2", "git_log", argsWithFilePath("/src/Bar.java")),
            TranscriptEvent.toolResult("call-2", 1, false, 100, "a1b2c3 some commit", null),
            TranscriptEvent.done("SUCCEEDED", "Root cause: missing null check")
        );

        BugfixSuggestion result = suggester.suggest("task-1", transcript);
        assertThat(result.getSuggestion()).contains("Root Cause");
        assertThat(result.getSuggestion()).contains("Affected Files");
        assertThat(result.getSuggestion()).contains("/src/Bar.java");
        assertThat(result.getSuggestion()).contains("a1b2c3");
    }
}
