package cn.watsontech.snapagent.boot2x.anchor;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import cn.watsontech.snapagent.core.llm.LlmClient;
import cn.watsontech.snapagent.core.llm.LlmEventSink;
import cn.watsontech.snapagent.core.llm.LlmRequest;
import cn.watsontech.snapagent.core.llm.Message;

import java.util.Collections;

/**
 * LLM-powered summarizer for long anchor content.
 *
 * <p>When content exceeds {@code snap-agent.anchor.summary-threshold-chars},
 * the summarizer calls the LLM to produce a compressed summary (~1500 chars).
 * Short content is returned as-is without any LLM call.</p>
 *
 * <p>On LLM error or exception, the original content is returned as a fallback
 * to ensure the user always gets a response.</p>
 */
public class AnchorContextSummarizer {

    private static final int SUMMARY_MAX_TOKENS = 1024;

    private final LlmClient llmClient;
    private final SnapAgentProperties.Anchor props;

    public AnchorContextSummarizer(LlmClient llmClient, SnapAgentProperties.Anchor props) {
        this.llmClient = llmClient;
        this.props = props;
    }

    /**
     * Summarizes the given content if it exceeds the threshold.
     *
     * @param content the raw page-section content (Markdown)
     * @return the summary, or the original content if short or on error
     */
    public String summarize(String content) {
        if (content == null) return null;
        if (content.isEmpty()) return "";

        if (content.length() <= props.getSummaryThresholdChars()) {
            return content;
        }

        try {
            StringBuilder accumulated = new StringBuilder();
            LlmRequest request = buildRequest(content);
            llmClient.stream(request, new LlmEventSink() {
                @Override
                public void onThought(String text) {
                    accumulated.append(text);
                }

                @Override
                public void onToolUse(String id, String name, java.util.Map<String, Object> input) {
                }

                @Override
                public void onToolResult(String toolUseId, String result) {
                }

                @Override
                public void onStop(String stopReason) {
                }

                @Override
                public void onError(String message) {
                    accumulated.setLength(0);
                }
            }, "anchor-summary-" + Thread.currentThread().getId());

            String summary = accumulated.toString();
            return summary.isEmpty() ? content : summary;
        } catch (Exception e) {
            return content;
        }
    }

    private LlmRequest buildRequest(String content) {
        String prompt = "请将以下页面内容压缩为一段不超过1500字符的摘要，保留关键信息（标题、数据、状态值、表格结构），去掉冗余描述。\n\n"
                + "页面内容：\n" + content;

        Message userMessage = Message.user(prompt);
        String model = props.getClassifierModel();
        if (model == null || model.isEmpty()) {
            model = null;
        }

        return new LlmRequest(
                "你是页面内容摘要助手。",
                Collections.singletonList(userMessage),
                Collections.emptyList(),
                model,
                SUMMARY_MAX_TOKENS,
                true
        );
    }
}
