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

import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unit tests for the POST /anchor/inject endpoint of {@link SnapAgentController}.
 */
@ExtendWith(MockitoExtension.class)
class SnapAgentControllerInjectTest {

    @Mock private SkillRegistry skillRegistry;
    @Mock private AgentExecutor agentExecutor;
    @Mock private TaskStore taskStore;
    @Mock private ToolDispatcher toolDispatcher;
    @Mock private SecurityGateway securityGateway;
    @Mock private AsyncTaskExecutor taskExecutor;
    @Mock private RateLimiter rateLimiter;
    @Mock private LlmClient llmClient;
    @Mock private AnchorInjectionOrchestrator injectionOrchestrator;

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
        controller.setInjectionOrchestrator(injectionOrchestrator);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        lenient().when(securityGateway.currentUserId()).thenReturn("user001");
        lenient().when(securityGateway.hasPermission(anyString())).thenReturn(true);
    }

    @Test
    void shouldReturnHtmlOnSuccessfulInject() throws Exception {
        InjectionResult mockResult = new InjectionResult("<p>hello</p>", false, java.time.Instant.now());
        when(injectionOrchestrator.inject(anyString(), any())).thenReturn(mockResult);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("anchorName", "公告区域");
        body.put("pageUrl", "/dashboard");
        body.put("skillId", "announcement");
        body.put("cacheTtl", 3600);

        mockMvc.perform(post("/snap-agent/anchor/inject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.html").value("<p>hello</p>"))
                .andExpect(jsonPath("$.cached").value(false));
    }

    @Test
    void shouldReturnCachedFlagOnCacheHit() throws Exception {
        InjectionResult mockResult = new InjectionResult("<p>cached</p>", true, java.time.Instant.now());
        when(injectionOrchestrator.inject(anyString(), any())).thenReturn(mockResult);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("anchorName", "公告");
        body.put("pageUrl", "/page");
        body.put("skillId", "announcement");

        mockMvc.perform(post("/snap-agent/anchor/inject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cached").value(true));
    }

    @Test
    void shouldReturn400WhenAnchorNameMissing() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pageUrl", "/page");
        body.put("skillId", "announcement");

        mockMvc.perform(post("/snap-agent/anchor/inject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldReturn400WhenNoSourceSpecified() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("anchorName", "公告");
        body.put("pageUrl", "/page");
        // no skillId or workflowId

        mockMvc.perform(post("/snap-agent/anchor/inject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldReturn503WhenInjectionOrchestratorNotConfigured() throws Exception {
        // Create controller without injection orchestrator
        SnapAgentController bareController = new SnapAgentController(
                skillRegistry, agentExecutor, taskStore, toolDispatcher,
                properties, securityGateway, rateLimiter, taskExecutor,
                null, llmClient, null, null, new InMemoryAuditStore(100));
        MockMvc bareMvc = MockMvcBuilders.standaloneSetup(bareController).build();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("anchorName", "公告");
        body.put("pageUrl", "/page");
        body.put("skillId", "announcement");

        bareMvc.perform(post("/snap-agent/anchor/inject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void shouldReturn500WhenSkillNotFound() throws Exception {
        when(injectionOrchestrator.inject(anyString(), any()))
                .thenThrow(new IllegalArgumentException("SKILL_NOT_FOUND: nonexistent"));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("anchorName", "公告");
        body.put("pageUrl", "/page");
        body.put("skillId", "nonexistent");

        mockMvc.perform(post("/snap-agent/anchor/inject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("SKILL_NOT_FOUND"));
    }

    // ---- E2E: full injection lifecycle (cache miss → cache hit → different user) ----

    @Test
    void shouldCompleteFullInjectionLifecycle() throws Exception {
        java.time.Instant now = java.time.Instant.now();
        InjectionResult freshResult = new InjectionResult("<p>fresh content</p>", false, now);
        InjectionResult cachedResult = new InjectionResult("<p>fresh content</p>", true, now);
        InjectionResult freshResultOther = new InjectionResult("<p>other content</p>", false, now);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("anchorName", "公告区域");
        body.put("pageUrl", "/dashboard");
        body.put("skillId", "announcement");
        body.put("cacheTtl", 3600);
        String jsonBody = objectMapper.writeValueAsString(body);

        // Step 1: POST /anchor/inject as user001 → 200, cached=false (fresh generation)
        when(securityGateway.currentUserId()).thenReturn("user001");
        when(injectionOrchestrator.inject(eq("user001"), any())).thenReturn(freshResult);

        mockMvc.perform(post("/snap-agent/anchor/inject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.html").value("<p>fresh content</p>"))
                .andExpect(jsonPath("$.cached").value(false));

        // Step 2: POST /anchor/inject same request as user001 → 200, cached=true (cache hit)
        when(injectionOrchestrator.inject(eq("user001"), any())).thenReturn(cachedResult);

        mockMvc.perform(post("/snap-agent/anchor/inject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.html").value("<p>fresh content</p>"))
                .andExpect(jsonPath("$.cached").value(true));

        // Step 3: POST /anchor/inject same request as different user → 200, cached=false
        // (different cache key because userId is part of the key)
        when(securityGateway.currentUserId()).thenReturn("other-user");
        when(injectionOrchestrator.inject(eq("other-user"), any())).thenReturn(freshResultOther);

        mockMvc.perform(post("/snap-agent/anchor/inject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.html").value("<p>other content</p>"))
                .andExpect(jsonPath("$.cached").value(false));
    }
}
