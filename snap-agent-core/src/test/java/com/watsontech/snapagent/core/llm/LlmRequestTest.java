package com.watsontech.snapagent.core.llm;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

class LlmRequestTest {

    @Test
    void shouldHoldAllFieldsWhenConstructed() {
        Message m1 = Message.user("hello");
        ToolDef tool = new ToolDef("mysql_query", "run sql", "{}");
        LlmRequest req = new LlmRequest("system-prompt", Arrays.asList(m1),
                Arrays.asList(tool), "claude-sonnet-4-6", 8192, true);

        assertThat(req.getSystemPrompt()).isEqualTo("system-prompt");
        assertThat(req.getMessages()).hasSize(1);
        assertThat(req.getTools()).hasSize(1);
        assertThat(req.getModel()).isEqualTo("claude-sonnet-4-6");
        assertThat(req.getMaxTokens()).isEqualTo(8192);
        assertThat(req.isStreaming()).isTrue();
    }

    @Test
    void shouldReturnEmptyListWhenMessagesNull() {
        LlmRequest req = new LlmRequest("sys", null, null, "m", 1, false);

        assertThat(req.getMessages()).isEmpty();
        assertThat(req.getTools()).isEmpty();
    }

    @Test
    void shouldCreateUserMessageWithFactory() {
        Message m = Message.user("hi");

        assertThat(m.getRole()).isEqualTo("user");
        assertThat(m.getContent()).isEqualTo("hi");
        assertThat(m.getToolUseId()).isNull();
    }

    @Test
    void shouldCreateSystemMessageWithFactory() {
        Message m = Message.system("sys");

        assertThat(m.getRole()).isEqualTo("system");
        assertThat(m.getContent()).isEqualTo("sys");
    }

    @Test
    void shouldCreateAssistantMessageWithFactory() {
        Message m = Message.assistant("response");

        assertThat(m.getRole()).isEqualTo("assistant");
        assertThat(m.getContent()).isEqualTo("response");
    }

    @Test
    void shouldCreateToolResultMessageWithFactory() {
        Message m = Message.toolResult("toolu_01", "result-json");

        assertThat(m.getRole()).isEqualTo("tool");
        assertThat(m.getContent()).isEqualTo("result-json");
        assertThat(m.getToolUseId()).isEqualTo("toolu_01");
    }

    @Test
    void shouldHoldFieldsInToolDef() {
        ToolDef def = new ToolDef("redis_get", "read key", "{\"type\":\"object\"}");

        assertThat(def.getName()).isEqualTo("redis_get");
        assertThat(def.getDescription()).isEqualTo("read key");
        assertThat(def.getInputSchema()).isEqualTo("{\"type\":\"object\"}");
    }

    @Test
    void shouldHaveToStringInToolDef() {
        ToolDef def = new ToolDef("mysql_query", "sql", "{}");

        assertThat(def.toString()).contains("mysql_query");
    }

    @Test
    void shouldHaveToStringInMessage() {
        Message m = Message.user("test");

        assertThat(m.toString()).contains("user");
        assertThat(m.toString()).contains("test");
    }
}
