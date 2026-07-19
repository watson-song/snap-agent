package cn.watsontech.snapagent.core.agent;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class ReportGeneratorTest {

    @Test
    void shouldGenerateMarkdownFromTranscript() {
        List<TranscriptEvent> events = new ArrayList<>();
        events.add(TranscriptEvent.thought("让我查一下数据库。"));
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("sql", "SELECT 1");
        events.add(TranscriptEvent.toolCall("toolu_01", "mysql_query", args));
        events.add(TranscriptEvent.toolResult("toolu_01", 1, false, 15L, "1", null));
        events.add(TranscriptEvent.done("SUCCEEDED", "查询完成"));

        String md = ReportGenerator.generate("SKU 诊断", events);

        assertThat(md).contains("# SKU 诊断");
        assertThat(md).contains("让我查一下数据库。");
        assertThat(md).contains("mysql_query");
        assertThat(md).contains("SELECT 1");
        assertThat(md).contains("SUCCEEDED");
    }

    @Test
    void shouldHandleEmptyTranscript() {
        String md = ReportGenerator.generate("空报告", Collections.emptyList());
        assertThat(md).contains("# 空报告");
        assertThat(md).contains("无诊断记录");
    }

    @Test
    void shouldRenderErrorEvents() {
        List<TranscriptEvent> events = new ArrayList<>();
        events.add(TranscriptEvent.thought("正在查询..."));
        events.add(TranscriptEvent.error("连接超时"));
        String md = ReportGenerator.generate("失败诊断", events);
        assertThat(md).contains("连接超时");
        assertThat(md).contains("❌");
    }
}
