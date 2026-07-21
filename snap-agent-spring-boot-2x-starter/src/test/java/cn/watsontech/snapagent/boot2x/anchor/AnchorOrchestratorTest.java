package cn.watsontech.snapagent.boot2x.anchor;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import cn.watsontech.snapagent.core.llm.LlmClient;
import cn.watsontech.snapagent.core.llm.LlmEventSink;
import cn.watsontech.snapagent.core.llm.LlmRequest;
import cn.watsontech.snapagent.core.skill.SkillAvailability;
import cn.watsontech.snapagent.core.skill.SkillMeta;
import cn.watsontech.snapagent.core.skill.SkillRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AnchorOrchestrator}.
 *
 * <p>Validates parallel dispatch of summarizer + classifier, preprocess
 * caching, and execute path that picks up cached results.</p>
 */
class AnchorOrchestratorTest {

    private SnapAgentProperties.Anchor props;
    private LlmClient llmClient;
    private SkillRegistry skillRegistry;
    private AnchorSummaryCache summaryCache;
    private AnchorContextSummarizer summarizer;
    private AnchorSkillClassifier classifier;
    private AnchorOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        props = new SnapAgentProperties.Anchor();
        props.setSummaryThresholdChars(100);
        props.setClassifierConfidenceThreshold(0.5);
        props.setPreprocessEnabled(true);
        llmClient = mock(LlmClient.class);
        skillRegistry = mock(SkillRegistry.class);
        summaryCache = new AnchorSummaryCache(props);
        summarizer = new AnchorContextSummarizer(llmClient, props);
        classifier = new AnchorSkillClassifier(llmClient, skillRegistry, props);
        orchestrator = new AnchorOrchestrator(llmClient, summaryCache, summarizer, classifier,
                skillRegistry, props);
    }

    @Test
    void shouldReturnPreprocessIdOnPreprocess() {
        when(skillRegistry.all()).thenReturn(Collections.emptyList());
        String content = new String(new char[200]).replace("\0", "x");

        doAnswer(invocation -> {
            LlmEventSink sink = invocation.getArgument(1);
            sink.onThought("summary or classify result");
            sink.onStop("end_turn");
            return null;
        }).when(llmClient).stream(any(), any(), anyString());

        AnchorContext anchor = new AnchorContext("test", content, "/p");
        PreprocessResult result = orchestrator.preprocess(anchor, "test question");

        assertThat(result).isNotNull();
        assertThat(result.getPreprocessId()).isNotBlank();
    }

    @Test
    void shouldRunSummaryAndClassifyInParallel() throws Exception {
        when(skillRegistry.all()).thenReturn(Collections.emptyList());
        String content = new String(new char[200]).replace("\0", "x");

        AtomicReference<Long> summaryStart = new AtomicReference<>();
        AtomicReference<Long> classifyStart = new AtomicReference<>();

        doAnswer(invocation -> {
            LlmRequest req = invocation.getArgument(0);
            LlmEventSink sink = invocation.getArgument(1);
            long now = System.currentTimeMillis();
            String prompt = req.getMessages().get(0).getContent();
            if (prompt.contains("摘要") && !prompt.contains("可用技能")) {
                summaryStart.set(now);
            } else if (prompt.contains("可用技能") || prompt.contains("skillId")) {
                classifyStart.set(now);
            }
            sink.onThought("test result");
            sink.onStop("end_turn");
            Thread.sleep(100);
            return null;
        }).when(llmClient).stream(any(), any(), anyString());

        AnchorContext anchor = new AnchorContext("test", content, "/p");
        PreprocessResult result = orchestrator.preprocess(anchor, "question");

        orchestrator.awaitPreprocess(result.getPreprocessId(), 2000);

        assertThat(summaryStart.get()).isNotNull();
        assertThat(classifyStart.get()).isNotNull();
        long diff = Math.abs(summaryStart.get() - classifyStart.get());
        assertThat(diff).isLessThan(80L);
    }

    @Test
    void shouldCacheSummaryAcrossPreprocessCalls() {
        when(skillRegistry.all()).thenReturn(Collections.emptyList());
        String content = new String(new char[200]).replace("\0", "x");

        int[] callCount = {0};
        doAnswer(invocation -> {
            LlmEventSink sink = invocation.getArgument(1);
            LlmRequest req = invocation.getArgument(0);
            String prompt = req.getMessages().get(0).getContent();
            if (prompt.contains("摘要")) {
                callCount[0]++;
            }
            sink.onThought("result");
            sink.onStop("end_turn");
            return null;
        }).when(llmClient).stream(any(), any(), anyString());

        AnchorContext anchor1 = new AnchorContext("a", content, "/p");
        PreprocessResult r1 = orchestrator.preprocess(anchor1, "q1");
        orchestrator.awaitPreprocess(r1.getPreprocessId(), 2000);
        int callsAfterFirst = callCount[0];

        AnchorContext anchor2 = new AnchorContext("b", content, "/p");
        PreprocessResult r2 = orchestrator.preprocess(anchor2, "q2");
        orchestrator.awaitPreprocess(r2.getPreprocessId(), 2000);
        int callsAfterSecond = callCount[0];

        assertThat(callsAfterSecond).isEqualTo(callsAfterFirst);
    }

    @Test
    void shouldSkipSummaryForShortContent() {
        when(skillRegistry.all()).thenReturn(Collections.emptyList());

        int[] summaryCallCount = {0};
        doAnswer(invocation -> {
            LlmRequest req = invocation.getArgument(0);
            LlmEventSink sink = invocation.getArgument(1);
            String prompt = req.getMessages().get(0).getContent();
            if (prompt.contains("摘要") && !prompt.contains("可用技能")) {
                summaryCallCount[0]++;
            }
            sink.onThought("result");
            sink.onStop("end_turn");
            return null;
        }).when(llmClient).stream(any(), any(), anyString());

        AnchorContext shortAnchor = new AnchorContext("short", "abc", "/p");
        PreprocessResult result = orchestrator.preprocess(shortAnchor, "question");
        orchestrator.awaitPreprocess(result.getPreprocessId(), 2000);

        assertThat(summaryCallCount[0]).isZero();
    }

    @Test
    void shouldReturnCachedSummaryOnExecute() {
        when(skillRegistry.all()).thenReturn(Collections.emptyList());
        String content = new String(new char[200]).replace("\0", "x");

        int[] summaryCallCount = {0};
        doAnswer(invocation -> {
            LlmRequest req = invocation.getArgument(0);
            LlmEventSink sink = invocation.getArgument(1);
            String prompt = req.getMessages().get(0).getContent();
            if (prompt.contains("摘要") && !prompt.contains("可用技能")) {
                summaryCallCount[0]++;
            }
            sink.onThought("summary-content");
            sink.onStop("end_turn");
            return null;
        }).when(llmClient).stream(any(), any(), anyString());

        AnchorContext anchor = new AnchorContext("test", content, "/p");
        PreprocessResult preprocess = orchestrator.preprocess(anchor, "question");
        orchestrator.awaitPreprocess(preprocess.getPreprocessId(), 2000);
        int callsAfterPreprocess = summaryCallCount[0];

        LlmEventSink mainSink = new NoOpSink();
        orchestrator.executeWithAnchor(anchor, "question", null, mainSink);

        assertThat(summaryCallCount[0]).isEqualTo(callsAfterPreprocess);
    }

    @Test
    void shouldFallbackToGeneralLlmWhenClassifierFails() {
        when(skillRegistry.all()).thenReturn(Collections.singletonList(
                new SkillMeta("patrol", "运维巡检", Collections.<String>emptyList(),
                        Collections.emptyList(), "body", SkillAvailability.AVAILABLE, null)
        ));

        doAnswer(invocation -> {
            LlmRequest req = invocation.getArgument(0);
            LlmEventSink sink = invocation.getArgument(1);
            String prompt = req.getMessages().get(0).getContent();
            if (prompt.contains("可用技能") || prompt.contains("skillId")) {
                sink.onThought("{\"skillId\": null, \"confidence\": 0.2}");
            } else if (prompt.contains("摘要")) {
                sink.onThought("summary");
            } else {
                sink.onThought("general LLM response");
            }
            sink.onStop("end_turn");
            return null;
        }).when(llmClient).stream(any(), any(), anyString());

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) sb.append("long enough content here ...");
        AnchorContext anchor = new AnchorContext("test", sb.toString(), "/p");
        PreprocessResult preprocess = orchestrator.preprocess(anchor, "question");
        orchestrator.awaitPreprocess(preprocess.getPreprocessId(), 2000);

        RecordingSink mainSink = new RecordingSink();
        orchestrator.executeWithAnchor(anchor, "question", null, mainSink);

        assertThat(mainSink.thoughts).contains("general LLM response");
    }

    @Test
    void shouldAugmentUserMessageWithAnchorContext() {
        when(skillRegistry.all()).thenReturn(Collections.emptyList());

        ArgumentCaptor<LlmRequest> reqCaptor = ArgumentCaptor.forClass(LlmRequest.class);
        doAnswer(invocation -> {
            LlmEventSink sink = invocation.getArgument(1);
            sink.onThought("result");
            sink.onStop("end_turn");
            return null;
        }).when(llmClient).stream(reqCaptor.capture(), any(), anyString());

        AnchorContext anchor = new AnchorContext(
                "订单区块", "## 订单\n状态：已发货", false, 0, null, "/order/123");
        RecordingSink sink = new RecordingSink();
        orchestrator.executeWithAnchor(anchor, "为什么还没到？", null, sink);

        boolean hasAugmentedRequest = reqCaptor.getAllValues().stream()
                .map(LlmRequest::getMessages)
                .flatMap(List -> List.stream())
                .anyMatch(msg -> msg.getContent().contains("已发货") && msg.getContent().contains("为什么还没到？"));

        assertThat(hasAugmentedRequest).isTrue();
    }

    @Test
    void shouldHandlePreprocessTimeoutGracefully() throws Exception {
        when(skillRegistry.all()).thenReturn(Collections.emptyList());

        doAnswer(invocation -> {
            Thread.sleep(5000);
            return null;
        }).when(llmClient).stream(any(), any(), anyString());

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) sb.append("long content");
        AnchorContext anchor = new AnchorContext("test", sb.toString(), "/p");
        PreprocessResult result = orchestrator.preprocess(anchor, "q");

        orchestrator.awaitPreprocess(result.getPreprocessId(), 100);

        RecordingSink sink = new RecordingSink();
        orchestrator.executeWithAnchor(anchor, "q", null, sink);
    }

    @Test
    void shouldHandleNullPreprocessId() {
        when(skillRegistry.all()).thenReturn(Collections.emptyList());

        doAnswer(invocation -> {
            LlmEventSink sink = invocation.getArgument(1);
            sink.onThought("response");
            sink.onStop("end_turn");
            return null;
        }).when(llmClient).stream(any(), any(), anyString());

        AnchorContext anchor = new AnchorContext("test", "content", "/p");
        RecordingSink sink = new RecordingSink();

        orchestrator.executeWithAnchor(anchor, "question", null, sink);

        assertThat(sink.thoughts).contains("response");
    }

    static class RecordingSink implements LlmEventSink {
        final StringBuilder thoughts = new StringBuilder();

        @Override
        public void onThought(String text) {
            thoughts.append(text);
        }

        @Override
        public void onToolUse(String id, String name, java.util.Map<String, Object> input) {}

        @Override
        public void onToolResult(String toolUseId, String result) {}

        @Override
        public void onStop(String stopReason) {}

        @Override
        public void onError(String message) {}
    }

    static class NoOpSink implements LlmEventSink {
        @Override
        public void onThought(String text) {}
        @Override
        public void onToolUse(String id, String name, java.util.Map<String, Object> input) {}
        @Override
        public void onToolResult(String toolUseId, String result) {}
        @Override
        public void onStop(String stopReason) {}
        @Override
        public void onError(String message) {}
    }
}
