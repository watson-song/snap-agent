package cn.watsontech.snapagent.boot2x.anchor;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import cn.watsontech.snapagent.core.llm.LlmClient;
import cn.watsontech.snapagent.core.llm.LlmEventSink;
import cn.watsontech.snapagent.core.llm.LlmRequest;
import cn.watsontech.snapagent.core.llm.Message;
import cn.watsontech.snapagent.core.skill.SkillAvailability;
import cn.watsontech.snapagent.core.skill.SkillMeta;
import cn.watsontech.snapagent.core.skill.SkillRegistry;

import java.util.Collections;
import java.util.List;

/**
 * LLM-powered skill classifier for the anchor feature.
 *
 * <p>Given the user's question and the anchor content, the classifier asks
 * the LLM to determine which SnapAgent skill (if any) should handle the request.
 * The LLM returns a JSON object: {@code {"skillId": "...", "confidence": 0.0..1.0}}.</p>
 *
 * <p>On any error, malformed JSON, or low confidence, the classifier returns
 * {@link ClassifyResult#noMatch()}, and the orchestrator falls back to the
 * general LLM path.</p>
 */
public class AnchorSkillClassifier {

    private static final int CLASSIFY_MAX_TOKENS = 512;
    private static final int CONTENT_SNIPPET_LIMIT = 500;

    private final LlmClient llmClient;
    private final SkillRegistry skillRegistry;
    private final SnapAgentProperties.Anchor props;

    public AnchorSkillClassifier(LlmClient llmClient, SkillRegistry skillRegistry,
                                  SnapAgentProperties.Anchor props) {
        this.llmClient = llmClient;
        this.skillRegistry = skillRegistry;
        this.props = props;
    }

    /**
     * Classifies the user's question + anchor content to determine the best skill.
     *
     * @param userQuestion the user's question text
     * @param content      the anchor's page-section content (Markdown)
     * @return a ClassifyResult with skillId + confidence, or noMatch on failure
     */
    public ClassifyResult classify(String userQuestion, String content) {
        try {
            StringBuilder accumulated = new StringBuilder();
            LlmRequest request = buildRequest(userQuestion, content);

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
            }, "anchor-classify-" + Thread.currentThread().getId());

            String raw = accumulated.toString();
            if (raw.isEmpty()) {
                return ClassifyResult.noMatch();
            }

            return parseResult(raw);
        } catch (Exception e) {
            return ClassifyResult.noMatch();
        }
    }

    private LlmRequest buildRequest(String userQuestion, String content) {
        List<SkillMeta> skills = skillRegistry.all();
        StringBuilder skillList = new StringBuilder();
        for (SkillMeta skill : skills) {
            if (skill.getAvailability() == SkillAvailability.AVAILABLE) {
                skillList.append("- skillId: ").append(skill.getName())
                        .append("，名称：").append(skill.getDescription() != null ? skill.getDescription() : "")
                        .append("\n");
            }
        }

        String snippet = content != null
                ? content.substring(0, Math.min(content.length(), CONTENT_SNIPPET_LIMIT))
                : "";

        String prompt = "你是一个智能路由器。根据用户的问题和页面内容，判断应该由哪个技能（skill）来回答。\n\n"
                + "可用技能列表：\n"
                + (skillList.length() > 0 ? skillList.toString() : "（无可用技能）")
                + "\n用户问题：" + userQuestion + "\n"
                + "页面内容片段：\n" + snippet + "\n\n"
                + "请以JSON格式返回：{\"skillId\": \"技能ID或null\", \"confidence\": 0.0到1.0之间的数值, \"reason\": \"简短理由\"}"
                + "\n如果没有匹配的技能，skillId设为null，confidence设为0到阈值之间的值。";

        String model = props.getClassifierModel();
        if (model == null || model.isEmpty()) {
            model = null;
        }

        return new LlmRequest(
                "你是SnapAgent的智能技能路由器。",
                Collections.singletonList(Message.user(prompt)),
                Collections.emptyList(),
                model,
                CLASSIFY_MAX_TOKENS,
                true
        );
    }

    /** Parses the LLM response JSON into a ClassifyResult. */
    private ClassifyResult parseResult(String raw) {
        // Extract JSON from the response (LLM may wrap it in text)
        String json = extractJson(raw);
        if (json == null) {
            return ClassifyResult.noMatch();
        }

        try {
            String skillId = extractJsonField(json, "skillId");
            double confidence = extractJsonDouble(json, "confidence", 0.0);

            if (skillId != null && (skillId.equals("null") || skillId.isEmpty())) {
                skillId = null;
            }

            String reason = extractJsonField(json, "reason");

            return new ClassifyResult(skillId, confidence, props.getClassifierConfidenceThreshold(), reason);
        } catch (Exception e) {
            return ClassifyResult.noMatch();
        }
    }

    /** Extracts the first JSON object from the raw text. */
    static String extractJson(String raw) {
        int start = raw.indexOf('{');
        if (start < 0) return null;
        int end = raw.lastIndexOf('}');
        if (end < 0 || end < start) return null;
        return raw.substring(start, end + 1);
    }

    /** Extracts a string field value from a JSON string. */
    static String extractJsonField(String json, String fieldName) {
        String[] patterns = {"\"" + fieldName + "\"\\s*:\\s*\"([^\"]*)\"", "\"" + fieldName + "\"\\s*:\\s*(null)"};
        for (String pattern : patterns) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(json);
            if (m.find()) {
                String val = m.group(1);
                return "null".equals(val) ? null : val;
            }
        }
        return null;
    }

    /** Extracts a double field value from a JSON string. */
    static double extractJsonDouble(String json, String fieldName, double defaultValue) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + fieldName + "\"\\s*:\\s*([0-9]+\\.?[0-9]*)")
                .matcher(json);
        if (m.find()) {
            try {
                return Double.parseDouble(m.group(1));
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }
}
