package cn.watsontech.snapagent.boot2x.anchor;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import cn.watsontech.snapagent.core.llm.LlmClient;
import cn.watsontech.snapagent.core.llm.LlmEventSink;
import cn.watsontech.snapagent.core.llm.LlmRequest;
import cn.watsontech.snapagent.core.skill.SkillAvailability;
import cn.watsontech.snapagent.core.skill.SkillMeta;
import cn.watsontech.snapagent.core.skill.SkillRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Collections;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AnchorSkillClassifier}.
 *
 * <p>Validates smart routing: given (user question + anchor content),
 * the classifier calls the LLM with a list of available skills and
 * returns a {@link ClassifyResult} with skillId + confidence.</p>
 */
class AnchorSkillClassifierTest {

    private SnapAgentProperties.Anchor props;
    private LlmClient llmClient;
    private SkillRegistry skillRegistry;
    private AnchorSkillClassifier classifier;

    @BeforeEach
    void setUp() {
        props = new SnapAgentProperties.Anchor();
        props.setClassifierConfidenceThreshold(0.5);
        props.setClassifierModel("");
        llmClient = mock(LlmClient.class);
        skillRegistry = mock(SkillRegistry.class);
        classifier = new AnchorSkillClassifier(llmClient, skillRegistry, props);
    }

    @Test
    void shouldReturnHighConfidenceWhenLlmReturnsValidSkillId() {
        when(skillRegistry.all()).thenReturn(Collections.singletonList(
                new SkillMeta("patrol", "运维巡检", Collections.<String>emptyList(),
                        Collections.emptyList(), "body", SkillAvailability.AVAILABLE, null)
        ));

        doAnswer(invocation -> {
            LlmEventSink sink = invocation.getArgument(1);
            sink.onThought("{\"skillId\": \"patrol\", \"confidence\": 0.92}");
            sink.onStop("end_turn");
            return null;
        }).when(llmClient).stream(any(), any(), anyString());

        ClassifyResult result = classifier.classify("为什么这个指标异常？", "## 指标\nQPS 下降 30%");

        assertThat(result.getSkillId()).isEqualTo("patrol");
        assertThat(result.getConfidence()).isCloseTo(0.92, within(0.01));
        assertThat(result.isMatch()).isTrue();
    }

    @Test
    void shouldReturnNoMatchWhenLlmReturnsNullSkillId() {
        when(skillRegistry.all()).thenReturn(Collections.emptyList());

        doAnswer(invocation -> {
            LlmEventSink sink = invocation.getArgument(1);
            sink.onThought("{\"skillId\": null, \"confidence\": 0.3, \"reason\": \"no matching skill\"}");
            sink.onStop("end_turn");
            return null;
        }).when(llmClient).stream(any(), any(), anyString());

        ClassifyResult result = classifier.classify("hello", "any content");

        assertThat(result.getSkillId()).isNull();
        assertThat(result.getConfidence()).isCloseTo(0.3, within(0.01));
        assertThat(result.isMatch()).isFalse();
    }

    @Test
    void shouldReturnNoMatchOnLowConfidence() {
        when(skillRegistry.all()).thenReturn(Collections.singletonList(
                new SkillMeta("patrol", "运维巡检", Collections.<String>emptyList(),
                        Collections.emptyList(), "body", SkillAvailability.AVAILABLE, null)
        ));

        doAnswer(invocation -> {
            LlmEventSink sink = invocation.getArgument(1);
            sink.onThought("{\"skillId\": \"patrol\", \"confidence\": 0.2}");
            sink.onStop("end_turn");
            return null;
        }).when(llmClient).stream(any(), any(), anyString());

        ClassifyResult result = classifier.classify("question", "content");

        assertThat(result.getSkillId()).isEqualTo("patrol");
        assertThat(result.getConfidence()).isCloseTo(0.2, within(0.01));
        assertThat(result.isMatch()).isFalse();
    }

    @Test
    void shouldFallbackOnLlmError() {
        when(skillRegistry.all()).thenReturn(Collections.emptyList());
        doAnswer(invocation -> {
            LlmEventSink sink = invocation.getArgument(1);
            sink.onError("API key invalid");
            return null;
        }).when(llmClient).stream(any(), any(), anyString());

        ClassifyResult result = classifier.classify("question", "content");

        assertThat(result.getSkillId()).isNull();
        assertThat(result.getConfidence()).isEqualTo(0.0);
        assertThat(result.isMatch()).isFalse();
    }

    @Test
    void shouldFallbackOnLlmException() {
        when(skillRegistry.all()).thenReturn(Collections.emptyList());
        doThrow(new RuntimeException("network error"))
                .when(llmClient).stream(any(), any(), anyString());

        ClassifyResult result = classifier.classify("question", "content");

        assertThat(result.getSkillId()).isNull();
        assertThat(result.getConfidence()).isEqualTo(0.0);
        assertThat(result.isMatch()).isFalse();
    }

    @Test
    void shouldHandleMalformedJsonResponse() {
        when(skillRegistry.all()).thenReturn(Collections.emptyList());
        doAnswer(invocation -> {
            LlmEventSink sink = invocation.getArgument(1);
            sink.onThought("这不是 JSON");
            sink.onStop("end_turn");
            return null;
        }).when(llmClient).stream(any(), any(), anyString());

        ClassifyResult result = classifier.classify("question", "content");

        assertThat(result.getSkillId()).isNull();
        assertThat(result.getConfidence()).isEqualTo(0.0);
    }

    @Test
    void shouldHandlePartialJsonResponse() {
        when(skillRegistry.all()).thenReturn(Collections.emptyList());
        doAnswer(invocation -> {
            LlmEventSink sink = invocation.getArgument(1);
            sink.onThought("分析结果：");
            sink.onThought("{\"skillId\": \"patrol\"");
            sink.onThought(", \"confidence\": 0.85}");
            sink.onStop("end_turn");
            return null;
        }).when(llmClient).stream(any(), any(), anyString());

        ClassifyResult result = classifier.classify("question", "content");

        assertThat(result.getSkillId()).isEqualTo("patrol");
        assertThat(result.getConfidence()).isCloseTo(0.85, within(0.01));
    }

    @Test
    void shouldConstructPromptContainingSkillList() {
        when(skillRegistry.all()).thenReturn(Arrays.asList(
                new SkillMeta("patrol", "运维巡检", Collections.<String>emptyList(),
                        Collections.emptyList(), "body", SkillAvailability.AVAILABLE, null),
                new SkillMeta("code", "代码分析", Collections.<String>emptyList(),
                        Collections.emptyList(), "body", SkillAvailability.AVAILABLE, null)
        ));

        ArgumentCaptor<LlmRequest> reqCaptor = ArgumentCaptor.forClass(LlmRequest.class);
        doAnswer(invocation -> {
            LlmEventSink sink = invocation.getArgument(1);
            sink.onThought("{\"skillId\": \"patrol\", \"confidence\": 0.9}");
            sink.onStop("end_turn");
            return null;
        }).when(llmClient).stream(reqCaptor.capture(), any(), anyString());

        classifier.classify("why is this metric abnormal", "## metric\nQPS drop");

        LlmRequest req = reqCaptor.getValue();
        String prompt = req.getMessages().get(0).getContent();
        assertThat(prompt).contains("patrol");
        assertThat(prompt).contains("运维巡检");
        assertThat(prompt).contains("code");
        assertThat(prompt).contains("代码分析");
        assertThat(prompt).contains("why is this metric abnormal");
    }

    @Test
    void shouldIncludeContentSnippetInPrompt() {
        when(skillRegistry.all()).thenReturn(Collections.emptyList());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) sb.append("这是一段很长的内容。");
        String content = sb.toString();

        ArgumentCaptor<LlmRequest> reqCaptor = ArgumentCaptor.forClass(LlmRequest.class);
        doAnswer(invocation -> {
            LlmEventSink sink = invocation.getArgument(1);
            sink.onThought("{\"skillId\": null, \"confidence\": 0.1}");
            sink.onStop("end_turn");
            return null;
        }).when(llmClient).stream(reqCaptor.capture(), any(), anyString());

        classifier.classify("question", content);

        LlmRequest req = reqCaptor.getValue();
        String prompt = req.getMessages().get(0).getContent();
        assertThat(prompt).contains("这是一段很长的内容");
    }

    @Test
    void shouldUseConfiguredClassifierModelWhenSet() {
        props.setClassifierModel("claude-haiku-4-5-20251001");
        when(skillRegistry.all()).thenReturn(Collections.emptyList());

        ArgumentCaptor<LlmRequest> reqCaptor = ArgumentCaptor.forClass(LlmRequest.class);
        doAnswer(invocation -> {
            LlmEventSink sink = invocation.getArgument(1);
            sink.onThought("{\"skillId\": null, \"confidence\": 0.0}");
            sink.onStop("end_turn");
            return null;
        }).when(llmClient).stream(reqCaptor.capture(), any(), anyString());

        classifier.classify("q", "c");

        assertThat(reqCaptor.getValue().getModel()).isEqualTo("claude-haiku-4-5-20251001");
    }

    @Test
    void shouldUseConfidenceThresholdFromConfig() {
        props.setClassifierConfidenceThreshold(0.8);
        when(skillRegistry.all()).thenReturn(Collections.singletonList(
                new SkillMeta("patrol", "运维巡检", Collections.<String>emptyList(),
                        Collections.emptyList(), "body", SkillAvailability.AVAILABLE, null)
        ));

        doAnswer(invocation -> {
            LlmEventSink sink = invocation.getArgument(1);
            sink.onThought("{\"skillId\": \"patrol\", \"confidence\": 0.7}");
            sink.onStop("end_turn");
            return null;
        }).when(llmClient).stream(any(), any(), anyString());

        ClassifyResult result = classifier.classify("q", "c");

        assertThat(result.isMatch()).isFalse();
    }

    private static org.assertj.core.data.Offset<Double> within(double tolerance) {
        return org.assertj.core.data.Offset.offset(tolerance);
    }

    // ---- G-407: skillId="null" string should be converted to actual null ----

    @Test
    @DisplayName("G-407: should convert string \"null\" skillId to actual null")
    void shouldConvertStringNullSkillIdToActualNull() {
        when(skillRegistry.all()).thenReturn(Collections.emptyList());

        doAnswer(invocation -> {
            LlmEventSink sink = invocation.getArgument(1);
            sink.onThought("{\"skillId\":\"null\",\"confidence\":0.1}");
            sink.onStop("end_turn");
            return null;
        }).when(llmClient).stream(any(), any(), anyString());

        ClassifyResult result = classifier.classify("q", "c");

        assertThat(result.getSkillId()).isNull();
        assertThat(result.isMatch()).isFalse();
    }

    @Test
    @DisplayName("G-407: should convert empty string skillId to actual null")
    void shouldConvertEmptyStringSkillIdToActualNull() {
        when(skillRegistry.all()).thenReturn(Collections.emptyList());

        doAnswer(invocation -> {
            LlmEventSink sink = invocation.getArgument(1);
            sink.onThought("{\"skillId\":\"\",\"confidence\":0.9}");
            sink.onStop("end_turn");
            return null;
        }).when(llmClient).stream(any(), any(), anyString());

        ClassifyResult result = classifier.classify("q", "c");

        assertThat(result.getSkillId()).isNull();
        assertThat(result.isMatch()).isFalse();
    }

    @Test
    @DisplayName("G-407: should parse reason field when present")
    void shouldParseReasonFieldWhenPresent() {
        when(skillRegistry.all()).thenReturn(Collections.singletonList(
                new SkillMeta("patrol", "运维巡检", Collections.<String>emptyList(),
                        Collections.emptyList(), "body", SkillAvailability.AVAILABLE, null)
        ));

        doAnswer(invocation -> {
            LlmEventSink sink = invocation.getArgument(1);
            sink.onThought("{\"skillId\":\"patrol\",\"confidence\":0.9,\"reason\":\"ops question\"}");
            sink.onStop("end_turn");
            return null;
        }).when(llmClient).stream(any(), any(), anyString());

        ClassifyResult result = classifier.classify("q", "c");

        assertThat(result.getReason()).isEqualTo("ops question");
    }

    // ---- G-409: extractJson edge cases per Gherkin scenario outline ----

    static Stream<Arguments> extractJsonData() {
        return Stream.of(
                // no braces → null
                Arguments.of("no braces", null),
                // only start brace, no end → null
                Arguments.of("{only start", null),
                // text with JSON in the middle → just the JSON
                Arguments.of("text {\"a\":1} trailing", "{\"a\":1}"),
                // two JSON objects concatenated → full span
                Arguments.of("{\"a\":1}{\"b\":2}", "{\"a\":1}{\"b\":2}"),
                // empty JSON object
                Arguments.of("{}", "{}"),
                // JSON wrapped in markdown code block
                Arguments.of("```json\n{\"skillId\":\"patrol\"}\n```", "{\"skillId\":\"patrol\"}")
        );
    }

    @ParameterizedTest(name = "extractJson(\"{0}\")")
    @MethodSource("extractJsonData")
    @DisplayName("G-409: extractJson edge cases per Gherkin UC-07 scenario outline")
    void shouldExtractJsonFromRawText(String raw, String expected) {
        assertThat(AnchorSkillClassifier.extractJson(raw)).isEqualTo(expected);
    }

    // ---- G-409: extractJsonField edge cases ----

    @Test
    @DisplayName("G-409: extractJsonField should return string value for valid field")
    void shouldExtractStringFieldValue() {
        String json = "{\"skillId\":\"patrol\",\"confidence\":0.9}";

        assertThat(AnchorSkillClassifier.extractJsonField(json, "skillId")).isEqualTo("patrol");
    }

    @Test
    @DisplayName("G-409: extractJsonField should return null for JSON null value")
    void shouldReturnNullForJsonNullValue() {
        String json = "{\"skillId\":null,\"confidence\":0.9}";

        assertThat(AnchorSkillClassifier.extractJsonField(json, "skillId")).isNull();
    }

    @Test
    @DisplayName("G-409: extractJsonField should return null for missing field")
    void shouldReturnNullForMissingField() {
        String json = "{\"otherField\":\"value\"}";

        assertThat(AnchorSkillClassifier.extractJsonField(json, "skillId")).isNull();
    }

    @Test
    @DisplayName("G-409: extractJsonField should handle whitespace around colon")
    void shouldHandleWhitespaceAroundColon() {
        String json = "{\"skillId\" :  \"patrol\"}";

        assertThat(AnchorSkillClassifier.extractJsonField(json, "skillId")).isEqualTo("patrol");
    }

    // ---- G-409: extractJsonDouble edge cases ----

    @Test
    @DisplayName("G-409: extractJsonDouble should parse decimal confidence")
    void shouldParseDecimalConfidence() {
        String json = "{\"confidence\":0.92}";

        assertThat(AnchorSkillClassifier.extractJsonDouble(json, "confidence", 0.0)).isCloseTo(0.92, within(0.001));
    }

    @Test
    @DisplayName("G-409: extractJsonDouble should parse integer value")
    void shouldParseIntegerValue() {
        String json = "{\"confidence\":1}";

        assertThat(AnchorSkillClassifier.extractJsonDouble(json, "confidence", 0.0)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("G-409: extractJsonDouble should return default for missing field")
    void shouldReturnDefaultForMissingDoubleField() {
        String json = "{\"other\":\"value\"}";

        assertThat(AnchorSkillClassifier.extractJsonDouble(json, "confidence", -1.0)).isEqualTo(-1.0);
    }

    @Test
    @DisplayName("G-409: extractJsonDouble should parse zero")
    void shouldParseZeroValue() {
        String json = "{\"confidence\":0.0}";

        assertThat(AnchorSkillClassifier.extractJsonDouble(json, "confidence", -1.0)).isEqualTo(0.0);
    }
}
