package cn.watsontech.snapagent.core.memory;

import cn.watsontech.snapagent.core.llm.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LastNMessagePartitioner 消息分区策略")
class LastNMessagePartitionerTest {

    @Test
    @DisplayName("空历史 + 用户消息 → 仅含用户消息")
    void emptyHistoryReturnsJustUserMessage() {
        MessagePartitioner partitioner = new LastNMessagePartitioner();
        List<Message> result = partitioner.partition(null, "hello");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getContent()).isEqualTo("hello");
    }

    @Test
    @DisplayName("非空历史 + 用户消息 → 用户消息在前 + 历史")
    void nonEmptyHistoryReturnsUserMessageBeforeHistory() {
        MessagePartitioner partitioner = new LastNMessagePartitioner();
        List<Message> history = Arrays.asList(
                Message.assistant("response"),
                Message.toolResult("tu-1", "data")
        );
        List<Message> result = partitioner.partition(history, "query");
        assertThat(result).hasSize(3);
        assertThat(result.get(0).getContent()).isEqualTo("query");
        assertThat(result.get(0).getRole()).isEqualTo("user");
        assertThat(result.get(1).getContent()).isEqualTo("response");
        assertThat(result.get(2).getContent()).isEqualTo("data");
    }

    @Test
    @DisplayName("maxMessages=2 时截断最旧历史消息（用户消息在前）")
    void maxMessagesTruncatesOldest() {
        MessagePartitioner partitioner = new LastNMessagePartitioner(2);
        List<Message> history = Arrays.asList(
                Message.assistant("resp1"),
                Message.toolResult("tu-1", "old1"),
                Message.assistant("resp2"),
                Message.toolResult("tu-2", "recent")
        );
        List<Message> result = partitioner.partition(history, "current");
        // user message + last 2 history messages = 3
        assertThat(result).hasSize(3);
        assertThat(result.get(0).getContent()).isEqualTo("current");
        assertThat(result.get(1).getContent()).isEqualTo("resp2");
        assertThat(result.get(2).getContent()).isEqualTo("recent");
    }

    @Test
    @DisplayName("空用户消息 → 不添加用户消息，仅返回历史")
    void emptyUserMessageOmitted() {
        MessagePartitioner partitioner = new LastNMessagePartitioner();
        List<Message> history = Collections.singletonList(Message.assistant("prev"));
        List<Message> result = partitioner.partition(history, "");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getContent()).isEqualTo("prev");
    }
}
