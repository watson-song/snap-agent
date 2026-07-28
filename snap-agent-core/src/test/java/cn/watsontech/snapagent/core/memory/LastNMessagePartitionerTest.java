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
    @DisplayName("非空历史 + 用户消息 → 历史 + 用户消息")
    void nonEmptyHistoryReturnsHistoryPlusUserMessage() {
        MessagePartitioner partitioner = new LastNMessagePartitioner();
        List<Message> history = Arrays.asList(
                Message.user("first"),
                Message.assistant("response")
        );
        List<Message> result = partitioner.partition(history, "second");
        assertThat(result).hasSize(3);
        assertThat(result.get(0).getContent()).isEqualTo("first");
        assertThat(result.get(1).getContent()).isEqualTo("response");
        assertThat(result.get(2).getContent()).isEqualTo("second");
    }

    @Test
    @DisplayName("maxMessages=2 时截断最旧消息")
    void maxMessagesTruncatesOldest() {
        MessagePartitioner partitioner = new LastNMessagePartitioner(2);
        List<Message> history = Arrays.asList(
                Message.user("old1"),
                Message.assistant("resp1"),
                Message.user("old2"),
                Message.assistant("resp2"),
                Message.user("recent")
        );
        List<Message> result = partitioner.partition(history, "current");
        assertThat(result).hasSize(3);
        assertThat(result.get(0).getContent()).isEqualTo("resp2");
        assertThat(result.get(1).getContent()).isEqualTo("recent");
        assertThat(result.get(2).getContent()).isEqualTo("current");
    }

    @Test
    @DisplayName("空用户消息 → 不添加用户消息")
    void emptyUserMessageOmitted() {
        MessagePartitioner partitioner = new LastNMessagePartitioner();
        List<Message> history = Collections.singletonList(Message.user("prev"));
        List<Message> result = partitioner.partition(history, "");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getContent()).isEqualTo("prev");
    }
}
