package com.watsontech.snapagent.boot2x.web;

import com.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import com.watsontech.snapagent.boot2x.security.LoggingSecurityAuditLogger;
import com.watsontech.snapagent.core.agent.AgentExecutor;
import com.watsontech.snapagent.core.agent.RateLimiter;
import com.watsontech.snapagent.core.agent.TaskStore;
import com.watsontech.snapagent.core.llm.LlmClient;
import com.watsontech.snapagent.core.security.SecurityAuditLogger;
import com.watsontech.snapagent.core.security.SecurityGateway;
import com.watsontech.snapagent.core.security.UserInfo;
import com.watsontech.snapagent.core.skill.SkillRegistry;
import com.watsontech.snapagent.core.tool.ToolDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for security checks and audit logging on SnapAgent API endpoints.
 *
 * <p>Verifies that all endpoints enforce authentication and that
 * {@link SecurityAuditLogger} is called for audited actions.</p>
 */
@ExtendWith(MockitoExtension.class)
class SnapAgentControllerSecurityTest {

    @Mock private SkillRegistry skillRegistry;
    @Mock private AgentExecutor agentExecutor;
    @Mock private TaskStore taskStore;
    @Mock private ToolDispatcher toolDispatcher;
    @Mock private SecurityGateway securityGateway;
    @Mock private RateLimiter rateLimiter;
    @Mock private AsyncTaskExecutor taskExecutor;
    @Mock private LlmClient llmClient;
    @Mock private SecurityAuditLogger auditLogger;

    private SnapAgentProperties properties;
    private SnapAgentController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        properties = new SnapAgentProperties();
        controller = new SnapAgentController(
                skillRegistry, agentExecutor, taskStore, toolDispatcher,
                properties, securityGateway, rateLimiter, taskExecutor,
                null, llmClient, auditLogger);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        // Default: authenticated user with permission
        lenient().when(securityGateway.currentUserId()).thenReturn("user001");
        lenient().when(securityGateway.hasPermission(anyString())).thenReturn(true);
    }

    // ---- Auth checks on previously open endpoints ----

    @Test
    void shouldReturn401ForGetSkillsWhenNotAuthenticated() throws Exception {
        when(securityGateway.currentUserId()).thenReturn(null);

        mockMvc.perform(get("/snap-agent/skills"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    void shouldReturn401ForGetModelsWhenNotAuthenticated() throws Exception {
        when(securityGateway.currentUserId()).thenReturn(null);

        mockMvc.perform(get("/snap-agent/models"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    void shouldReturn401ForGetToolsWhenNotAuthenticated() throws Exception {
        when(securityGateway.currentUserId()).thenReturn(null);

        mockMvc.perform(get("/snap-agent/tools"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    void shouldReturn401ForRefreshSkillsWhenNotAuthenticated() throws Exception {
        when(securityGateway.currentUserId()).thenReturn(null);

        mockMvc.perform(post("/snap-agent/skills/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    void shouldReturn403WhenPermissionDenied() throws Exception {
        when(securityGateway.hasPermission(anyString())).thenReturn(false);

        mockMvc.perform(get("/snap-agent/models"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    void shouldReturn401WhenSecurityGatewayIsNull() throws Exception {
        controller = new SnapAgentController(
                skillRegistry, agentExecutor, taskStore, toolDispatcher,
                properties, null, rateLimiter, taskExecutor,
                null, null, null);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(get("/snap-agent/models"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("security not configured"));
    }

    // ---- Audit logging ----

    @Test
    void shouldAuditListModels() throws Exception {
        mockMvc.perform(get("/snap-agent/models"));

        verify(auditLogger).onApiAccess(
                eq("user001"), eq("GET"), eq("/models"),
                eq("LIST_MODELS"), any());
    }

    @Test
    void shouldAuditListSkills() throws Exception {
        when(skillRegistry.all()).thenReturn(Collections.<com.watsontech.snapagent.core.skill.SkillMeta>emptyList());

        mockMvc.perform(get("/snap-agent/skills"));

        verify(auditLogger).onApiAccess(
                eq("user001"), eq("GET"), eq("/skills"),
                eq("LIST_SKILLS"), any());
    }

    @Test
    void shouldAuditListTools() throws Exception {
        when(toolDispatcher.availableToolNames()).thenReturn(Collections.<String>emptySet());

        mockMvc.perform(get("/snap-agent/tools"));

        verify(auditLogger).onApiAccess(
                eq("user001"), eq("GET"), eq("/tools"),
                eq("LIST_TOOLS"), any());
    }

    @Test
    void shouldNotAuditWhenAuditLoggerIsNull() throws Exception {
        controller = new SnapAgentController(
                skillRegistry, agentExecutor, taskStore, toolDispatcher,
                properties, securityGateway, rateLimiter, taskExecutor,
                null, llmClient, null);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(get("/snap-agent/models"))
                .andExpect(status().isOk());
        // No exception, no audit — just works
    }

    @Test
    void shouldNotAuditWhenNotAuthenticated() throws Exception {
        when(securityGateway.currentUserId()).thenReturn(null);

        mockMvc.perform(get("/snap-agent/models"))
                .andExpect(status().isUnauthorized());

        verify(auditLogger, never()).onApiAccess(anyString(), anyString(), anyString(), anyString(), any());
    }

    // ---- LoggingSecurityAuditLogger ----

    @Test
    void loggingSecurityAuditLoggerShouldNotThrow() {
        LoggingSecurityAuditLogger logger = new LoggingSecurityAuditLogger();
        // Should not throw any exception
        logger.onApiAccess("user001", "GET", "/snap-agent/models",
                "LIST_MODELS", Collections.<String, Object>singletonMap("key", "value"));
    }

    @Test
    void loggingSecurityAuditLoggerShouldHandleNullDetails() {
        LoggingSecurityAuditLogger logger = new LoggingSecurityAuditLogger();
        logger.onApiAccess("user001", "POST", "/snap-agent/runs",
                "RUN_SKILL", null);
    }

    // ---- /user-info endpoint ----

    @Test
    void shouldReturnAuthenticatedAndAuthorizedWhenUserHasPermission() throws Exception {
        mockMvc.perform(get("/snap-agent/user-info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.authorized").value(true))
                .andExpect(jsonPath("$.userId").value("user001"))
                .andExpect(jsonPath("$.username").value("user001"));
    }

    @Test
    void shouldReturnNotAuthenticatedWhenUserNotLoggedIn() throws Exception {
        when(securityGateway.currentUserId()).thenReturn(null);

        mockMvc.perform(get("/snap-agent/user-info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.authorized").value(false));
    }

    @Test
    void shouldReturnNotAuthorizedWhenUserLacksPermission() throws Exception {
        when(securityGateway.hasPermission(anyString())).thenReturn(false);

        mockMvc.perform(get("/snap-agent/user-info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.authorized").value(false))
                .andExpect(jsonPath("$.message").value("您未授权，请联系管理员授权访问"));
    }

    @Test
    void userInfoShouldNotRequireAuth() throws Exception {
        // /user-info is public — no requireAuth() check
        when(securityGateway.currentUserId()).thenReturn(null);

        mockMvc.perform(get("/snap-agent/user-info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false));
    }

    @Test
    void userInfoShouldHandleNullSecurityGateway() throws Exception {
        controller = new SnapAgentController(
                skillRegistry, agentExecutor, taskStore, toolDispatcher,
                properties, null, rateLimiter, taskExecutor,
                null, null, null);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(get("/snap-agent/user-info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.authorized").value(false))
                .andExpect(jsonPath("$.message").value("security not configured"));
    }
}
