package cn.watsontech.snapagent.boot2x.anchor;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import cn.watsontech.snapagent.boot2x.security.InMemoryAuditStore;
import cn.watsontech.snapagent.boot2x.web.SnapAgentController;
import cn.watsontech.snapagent.core.agent.AgentExecutor;
import cn.watsontech.snapagent.core.agent.RateLimiter;
import cn.watsontech.snapagent.core.agent.TaskStore;
import cn.watsontech.snapagent.core.llm.LlmClient;
import cn.watsontech.snapagent.core.security.SecurityGateway;
import cn.watsontech.snapagent.core.skill.SkillRegistry;
import cn.watsontech.snapagent.core.tool.ToolDispatcher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unit tests for the anchor-related endpoints of {@link SnapAgentController}.
 *
 * <p>Covers:
 * <ul>
 *   <li>{@code GET /anchor/config} — public endpoint returning anchor config</li>
 *   <li>{@code POST /anchor/preprocess} — pre-summarize + pre-classify on anchor click</li>
 *   <li>{@code POST /runs} with {@code skillId: "auto"} + {@code anchor} field — smart routing</li>
 * </ul>
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class SnapAgentControllerAnchorTest {

    @Mock private SkillRegistry skillRegistry;
    @Mock private AgentExecutor agentExecutor;
    @Mock private TaskStore taskStore;
    @Mock private ToolDispatcher toolDispatcher;
    @Mock private SecurityGateway securityGateway;
    @Mock private AsyncTaskExecutor taskExecutor;
    @Mock private RateLimiter rateLimiter;
    @Mock private LlmClient llmClient;
    @Mock private AnchorOrchestrator anchorOrchestrator;

    private SnapAgentProperties properties;
    private SnapAgentController controller;
    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        properties = new SnapAgentProperties();
        objectMapper = new ObjectMapper();
        InMemoryAuditStore auditStore = new InMemoryAuditStore(100);
        controller = new SnapAgentController(
                skillRegistry, agentExecutor, taskStore, toolDispatcher,
                properties, securityGateway, rateLimiter, taskExecutor,
                null, llmClient, null, null, auditStore);
        // Inject AnchorOrchestrator via setter (optional dependency)
        controller.setAnchorOrchestrator(anchorOrchestrator);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        lenient().when(securityGateway.currentUserId()).thenReturn("user001");
        lenient().when(securityGateway.hasPermission(anyString())).thenReturn(true);
    }

    // ===== GET /anchor/config =====

    @Test
    void shouldReturnAnchorConfigWhenEnabled() throws Exception {
        mockMvc.perform(get("/snap-agent/anchor/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.disabledPaths").isArray());
    }

    @Test
    void shouldReturnDisabledFlagWhenAnchorDisabled() throws Exception {
        properties.getAnchor().setEnabled(false);

        mockMvc.perform(get("/snap-agent/anchor/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void shouldReturnDisabledPathsInConfig() throws Exception {
        properties.getAnchor().setDisabledPaths(Arrays.asList("/payment/**", "/admin/**"));

        mockMvc.perform(get("/snap-agent/anchor/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disabledPaths[0]").value("/payment/**"))
                .andExpect(jsonPath("$.disabledPaths[1]").value("/admin/**"));
    }

    @Test
    void shouldNotRequireAuthForAnchorConfig() throws Exception {
        // GET /anchor/config should be publicly accessible (no auth check)
        lenient().when(securityGateway.currentUserId()).thenReturn(null);

        mockMvc.perform(get("/snap-agent/anchor/config"))
                .andExpect(status().isOk());
    }

    // ===== POST /anchor/preprocess =====

    @Test
    void shouldAcceptPreprocessRequestWithAnchorContext() throws Exception {
        Map<String, Object> anchor = new LinkedHashMap<>();
        anchor.put("name", "test-anchor");
        anchor.put("content", "## Test\nThis is test content");
        anchor.put("pageUrl", "/docs/test");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("anchor", anchor);
        body.put("question", "what is this about?");

        PreprocessResult mockResult = new PreprocessResult("prep-123");
        when(anchorOrchestrator.preprocess(any(AnchorContext.class), anyString()))
                .thenReturn(mockResult);

        mockMvc.perform(post("/snap-agent/anchor/preprocess")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preprocessId").value("prep-123"))
                .andExpect(jsonPath("$.status").value("started"));
    }

    @Test
    void shouldAcceptPreprocessRequestWithoutQuestion() throws Exception {
        Map<String, Object> anchor = new LinkedHashMap<>();
        anchor.put("name", "test");
        anchor.put("content", "content");
        anchor.put("pageUrl", "/p");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("anchor", anchor);
        // question omitted — user hasn't typed yet

        PreprocessResult mockResult = new PreprocessResult("prep-456");
        when(anchorOrchestrator.preprocess(any(AnchorContext.class), anyString()))
                .thenReturn(mockResult);

        mockMvc.perform(post("/snap-agent/anchor/preprocess")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preprocessId").value("prep-456"));
    }

    @Test
    void shouldRejectPreprocessWhenAnchorMissing() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("question", "where is anchor?");
        // anchor field missing

        mockMvc.perform(post("/snap-agent/anchor/preprocess")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void shouldRejectPreprocessWhenAnchorNameMissing() throws Exception {
        Map<String, Object> anchor = new LinkedHashMap<>();
        anchor.put("content", "content");  // no name

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("anchor", anchor);

        mockMvc.perform(post("/snap-agent/anchor/preprocess")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void shouldReturnUnauthorizedWhenNotLoggedIn() throws Exception {
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

    // ===== POST /runs with skillId="auto" + anchor =====

    @Test
    void shouldRejectRunsWhenAnchorFeatureDisabled() throws Exception {
        properties.getAnchor().setEnabled(false);

        Map<String, Object> anchor = new LinkedHashMap<>();
        anchor.put("name", "t");
        anchor.put("content", "c");
        anchor.put("pageUrl", "/p");

        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("message", "question");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("skillId", "auto");
        body.put("inputs", inputs);
        body.put("anchor", anchor);

        // When anchor feature is disabled, skillId="auto" cannot be resolved
        // Should either return 400 or fall back to a default skill
        mockMvc.perform(post("/snap-agent/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().is4xxClientError());
    }
}
