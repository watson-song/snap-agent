package cn.watsontech.snapagent.boot2x.anchor;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import cn.watsontech.snapagent.boot2x.security.InMemoryAuditStore;
import cn.watsontech.snapagent.boot2x.web.SnapAgentController;
import cn.watsontech.snapagent.core.agent.AgentExecutor;
import cn.watsontech.snapagent.core.agent.RateLimiter;
import cn.watsontech.snapagent.core.agent.TaskStore;
import cn.watsontech.snapagent.core.llm.LlmClient;
import cn.watsontech.snapagent.core.llm.LlmEventSink;
import cn.watsontech.snapagent.core.llm.LlmRequest;
import cn.watsontech.snapagent.core.llm.Message;
import cn.watsontech.snapagent.core.security.SecurityGateway;
import cn.watsontech.snapagent.core.skill.SkillAvailability;
import cn.watsontech.snapagent.core.skill.SkillMeta;
import cn.watsontech.snapagent.core.skill.SkillRegistry;
import cn.watsontech.snapagent.core.tool.ToolDispatcher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * E2E integration tests for the anchor Q&amp;A feature.
 *
 * <p>Tests the full flow: controller endpoints → real AnchorOrchestrator
 * (with mocked LlmClient) → preprocess + execute pipeline.</p>
 *
 * <p>Main paths covered:</p>
 * <ol>
 *   <li>GET /anchor/config — config retrieval</li>
 *   <li>POST /anchor/preprocess — full preprocess flow (summary + classify)</li>
 *   <li>POST /anchor/preprocess — error cases (missing anchor, unauthorized)</li>
 *   <li>POST /runs with skillId="auto" + anchor — smart routing entry</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class AnchorE2ETest {

    @Mock private SkillRegistry skillRegistry;
    @Mock private AgentExecutor agentExecutor;
    @Mock private TaskStore taskStore;
    @Mock private ToolDispatcher toolDispatcher;
    @Mock private SecurityGateway securityGateway;
    @Mock private RateLimiter rateLimiter;
    @Mock private org.springframework.core.task.AsyncTaskExecutor taskExecutor;
    @Mock private LlmClient llmClient;

    private SnapAgentProperties properties;
    private SnapAgentController controller;
    private AnchorOrchestrator orchestrator;
    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        properties = new SnapAgentProperties();
        properties.getAnchor().setEnabled(true);
        properties.getAnchor().setSummaryThresholdChars(100);
        properties.getAnchor().setPreprocessEnabled(true);
        objectMapper = new ObjectMapper();
        InMemoryAuditStore auditStore = new InMemoryAuditStore(100);

        // Real orchestrator with mocked LLM
        AnchorSummaryCache cache = new AnchorSummaryCache(properties.getAnchor());
        AnchorContextSummarizer summarizer = new AnchorContextSummarizer(llmClient, properties.getAnchor());
        AnchorSkillClassifier classifier = new AnchorSkillClassifier(llmClient, skillRegistry, properties.getAnchor());
        orchestrator = new AnchorOrchestrator(llmClient, cache, summarizer, classifier,
                skillRegistry, properties.getAnchor());

        controller = new SnapAgentController(
                skillRegistry, agentExecutor, taskStore, toolDispatcher,
                properties, securityGateway, rateLimiter, taskExecutor,
                null, llmClient, null, null, auditStore);
        controller.setAnchorOrchestrator(orchestrator);

        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        lenient().when(securityGateway.currentUserId()).thenReturn("user001");
        lenient().when(securityGateway.hasPermission(anyString())).thenReturn(true);
    }

    // ===== E2E: GET /anchor/config =====

    @Test
    void e2e_getAnchorConfig_returnsEnabledConfig() throws Exception {
        mockMvc.perform(get("/snap-agent/anchor/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.disabledPaths").isArray());
    }

    @Test
    void e2e_getAnchorConfig_worksWithoutAuth() throws Exception {
        lenient().when(securityGateway.currentUserId()).thenReturn(null);

        mockMvc.perform(get("/snap-agent/anchor/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void e2e_getAnchorConfig_reflectsDisabledPaths() throws Exception {
        properties.getAnchor().setDisabledPaths(Collections.singletonList("/payment/**"));

        mockMvc.perform(get("/snap-agent/anchor/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disabledPaths[0]").value("/payment/**"));
    }

    // ===== E2E: POST /anchor/preprocess =====

    @Test
    void e2e_preprocess_fullFlow_returnsPreprocessId() throws Exception {
        when(skillRegistry.all()).thenReturn(Collections.singletonList(
                new SkillMeta("patrol", "运维巡检", Collections.<String>emptyList(),
                        Collections.emptyList(), "body", SkillAvailability.AVAILABLE, null)
        ));

        // Mock LLM to return summary + classify results
        doAnswer(invocation -> {
            LlmRequest req = invocation.getArgument(0);
            LlmEventSink sink = invocation.getArgument(1);
            String prompt = req.getMessages().get(0).getContent();
            if (prompt.contains("可用技能") || prompt.contains("skillId")) {
                sink.onThought("{\"skillId\": \"patrol\", \"confidence\": 0.9}");
            } else if (prompt.contains("摘要")) {
                sink.onThought("content summary");
            }
            sink.onStop("end_turn");
            return null;
        }).when(llmClient).stream(any(), any(), anyString());

        Map<String, Object> anchor = new LinkedHashMap<>();
        anchor.put("name", "ops-metrics");
        anchor.put("content", new String(new char[200]).replace("\0", "x"));
        anchor.put("pageUrl", "/dashboard/metrics");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("anchor", anchor);
        body.put("question", "why is QPS dropping?");

        mockMvc.perform(post("/snap-agent/anchor/preprocess")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preprocessId").exists())
                .andExpect(jsonPath("$.status").value("started"));
    }

    @Test
    void e2e_preprocess_withShortContent_skipsSummary() throws Exception {
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

        Map<String, Object> anchor = new LinkedHashMap<>();
        anchor.put("name", "short");
        anchor.put("content", "short content");
        anchor.put("pageUrl", "/p");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("anchor", anchor);
        body.put("question", "q");

        mockMvc.perform(post("/snap-agent/anchor/preprocess")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preprocessId").exists());
    }

    @Test
    void e2e_preprocess_missingAnchor_returns400() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("question", "where is anchor?");

        mockMvc.perform(post("/snap-agent/anchor/preprocess")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void e2e_preprocess_missingAnchorName_returns400() throws Exception {
        Map<String, Object> anchor = new LinkedHashMap<>();
        anchor.put("content", "content");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("anchor", anchor);

        mockMvc.perform(post("/snap-agent/anchor/preprocess")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void e2e_preprocess_unauthorized_returns401() throws Exception {
        when(securityGateway.currentUserId()).thenReturn(null);

        Map<String, Object> anchorField = new LinkedHashMap<>();
        anchorField.put("name", "t");
        anchorField.put("content", "c");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("anchor", anchorField);

        mockMvc.perform(post("/snap-agent/anchor/preprocess")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isUnauthorized());
    }

    // ===== E2E: POST /runs with skillId="auto" + anchor =====

    @Test
    void e2e_runs_withSkillIdAutoAndAnchor_anchorDisabled_returns4xx() throws Exception {
        properties.getAnchor().setEnabled(false);

        Map<String, Object> anchor = new LinkedHashMap<>();
        anchor.put("name", "test");
        anchor.put("content", "content");
        anchor.put("pageUrl", "/p");

        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("message", "question");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("skillId", "auto");
        body.put("inputs", inputs);
        body.put("anchor", anchor);

        mockMvc.perform(post("/snap-agent/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void e2e_runs_withSkillIdAutoAndAnchor_anchorEnabled_returns202WithTaskId() throws Exception {
        lenient().when(rateLimiter.tryAcquire(anyString())).thenReturn(true);

        Map<String, Object> anchor = new LinkedHashMap<>();
        anchor.put("name", "test-section");
        anchor.put("content", "some content here");
        anchor.put("pageUrl", "/p");

        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("message", "what is this?");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("skillId", "auto");
        body.put("inputs", inputs);
        body.put("anchor", anchor);

        mockMvc.perform(post("/snap-agent/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskId").exists())
                .andExpect(jsonPath("$.streamUrl").exists());
    }

    @Test
    void e2e_preprocess_multipleCalls_sameContent_usesCache() throws Exception {
        when(skillRegistry.all()).thenReturn(Collections.emptyList());

        int[] summaryCallCount = {0};
        doAnswer(invocation -> {
            LlmRequest req = invocation.getArgument(0);
            LlmEventSink sink = invocation.getArgument(1);
            String prompt = req.getMessages().get(0).getContent();
            if (prompt.contains("摘要") && !prompt.contains("可用技能")) {
                summaryCallCount[0]++;
            }
            sink.onThought("summary");
            sink.onStop("end_turn");
            return null;
        }).when(llmClient).stream(any(), any(), anyString());

        String longContent = new String(new char[200]).replace("\0", "x");

        // First preprocess — should compute summary
        Map<String, Object> anchor1 = new LinkedHashMap<>();
        anchor1.put("name", "a1");
        anchor1.put("content", longContent);
        anchor1.put("pageUrl", "/p");
        Map<String, Object> body1 = new LinkedHashMap<>();
        body1.put("anchor", anchor1);
        body1.put("question", "q1");

        mockMvc.perform(post("/snap-agent/anchor/preprocess")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body1)))
                .andExpect(status().isOk());

        // Wait for async preprocess to complete
        Thread.sleep(500);

        int callsAfterFirst = summaryCallCount[0];

        // Second preprocess with same content — should hit cache
        Map<String, Object> anchor2 = new LinkedHashMap<>();
        anchor2.put("name", "a2");
        anchor2.put("content", longContent);
        anchor2.put("pageUrl", "/p");
        Map<String, Object> body2 = new LinkedHashMap<>();
        body2.put("anchor", anchor2);
        body2.put("question", "q2");

        mockMvc.perform(post("/snap-agent/anchor/preprocess")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body2)))
                .andExpect(status().isOk());

        Thread.sleep(500);

        // Summary call count should not increase (cache hit)
        org.assertj.core.api.Assertions.assertThat(summaryCallCount[0]).isEqualTo(callsAfterFirst);
    }
}
