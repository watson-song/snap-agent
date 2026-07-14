package cn.watsontech.snapagent.core.agent;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TranscriptEventTest {

    @Test
    void shouldCreateThoughtEvent() {
        TranscriptEvent event = TranscriptEvent.thought("thinking...");

        assertThat(event.getType()).isEqualTo(TranscriptEvent.TYPE_THOUGHT);
        assertThat(event.getText()).isEqualTo("thinking...");
        assertThat(event.getTimestamp()).isGreaterThan(0);
        assertThat(event.getData()).isEmpty();
    }

    @Test
    void shouldCreateThoughtWithTimestamp() {
        TranscriptEvent event = TranscriptEvent.thought("t", 12345L);

        assertThat(event.getTimestamp()).isEqualTo(12345L);
    }

    @Test
    void shouldCreateToolCallEvent() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("sql", "SELECT 1");
        TranscriptEvent event = TranscriptEvent.toolCall("toolu_01", "mysql_query", args);

        assertThat(event.getType()).isEqualTo(TranscriptEvent.TYPE_TOOL_CALL);
        assertThat(event.getData().get("id")).isEqualTo("toolu_01");
        assertThat(event.getData().get("name")).isEqualTo("mysql_query");
        assertThat(event.getData().get("args")).isEqualTo(args);
    }

    @Test
    void shouldCreateToolResultEvent() {
        TranscriptEvent event = TranscriptEvent.toolResult("toolu_01", 42, true, 100L);

        assertThat(event.getType()).isEqualTo(TranscriptEvent.TYPE_TOOL_RESULT);
        assertThat(event.getData().get("id")).isEqualTo("toolu_01");
        assertThat(event.getData().get("rowCount")).isEqualTo(42);
        assertThat(event.getData().get("truncated")).isEqualTo(true);
        assertThat(event.getData().get("durationMs")).isEqualTo(100L);
    }

    @Test
    void shouldCreateDoneEvent() {
        TranscriptEvent event = TranscriptEvent.done("SUCCEEDED", "report text");

        assertThat(event.getType()).isEqualTo(TranscriptEvent.TYPE_DONE);
        assertThat(event.getData().get("status")).isEqualTo("SUCCEEDED");
        assertThat(event.getData().get("report")).isEqualTo("report text");
    }

    @Test
    void shouldCreateErrorEvent() {
        TranscriptEvent event = TranscriptEvent.error("something broke");

        assertThat(event.getType()).isEqualTo(TranscriptEvent.TYPE_ERROR);
        assertThat(event.getText()).isEqualTo("something broke");
    }

    @Test
    void shouldHaveToString() {
        TranscriptEvent event = TranscriptEvent.thought("test");

        assertThat(event.toString()).contains("thought");
        assertThat(event.toString()).contains("test");
    }
}
