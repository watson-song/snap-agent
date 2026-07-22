package cn.watsontech.snapagent.boot2x.web;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import cn.watsontech.snapagent.boot2x.security.InMemoryAuditStore;
import cn.watsontech.snapagent.core.agent.AgentExecutor;
import cn.watsontech.snapagent.core.agent.RateLimiter;
import cn.watsontech.snapagent.core.agent.TaskStore;
import cn.watsontech.snapagent.core.llm.LlmClient;
import cn.watsontech.snapagent.core.security.SecurityGateway;
import cn.watsontech.snapagent.core.skill.SkillAvailability;
import cn.watsontech.snapagent.core.skill.SkillMeta;
import cn.watsontech.snapagent.core.skill.InputSpec;
import cn.watsontech.snapagent.core.skill.SkillRegistry;
import cn.watsontech.snapagent.core.tool.InMemoryPluginRegistry;
import cn.watsontech.snapagent.core.tool.PluginDescriptor;
import cn.watsontech.snapagent.core.tool.PluginRegistry;
import cn.watsontech.snapagent.core.tool.ToolDispatcher;
import cn.watsontech.snapagent.core.tool.ToolProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.springframework.core.task.AsyncTaskExecutor;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests POST /runs with {@code pluginOverrides} — validates that the controller
 * accepts valid overrides and rejects unknown/disabled plugins with 400.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PluginOverridesTest {

    @Mock private SkillRegistry skillRegistry;
    @Mock private AgentExecutor agentExecutor;
    @Mock private TaskStore taskStore;
    @Mock private ToolDispatcher toolDispatcher;
    @Mock private SecurityGateway securityGateway;
    @Mock private AsyncTaskExecutor taskExecutor;
    @Mock private RateLimiter rateLimiter;
    @Mock private LlmClient llmClient;

    private SnapAgentProperties properties;
    private PluginRegistry registry;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        properties = new SnapAgentProperties();
        registry = new InMemoryPluginRegistry();

        InMemoryAuditStore auditStore = new InMemoryAuditStore(100);
        SnapAgentController controller = new SnapAgentController(
                skillRegistry, agentExecutor, taskStore, toolDispatcher,
                properties, securityGateway, rateLimiter, taskExecutor,
                null, llmClient, null, null, null, null, null, null,
                null, null, null, null,
                auditStore, registry, null);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        lenient().when(securityGateway.currentUserId()).thenReturn("user001");
        lenient().when(securityGateway.hasPermission(anyString())).thenReturn(true);
        lenient().when(rateLimiter.tryAcquire(anyString())).thenReturn(true);
        doAnswer(invocation -> { return null; }).when(taskExecutor).execute(any(Runnable.class));

        SkillMeta skill = new SkillMeta("test-skill", "Test",
                Collections.singletonList("mysql_query"),
                Collections.singletonList(new InputSpec("skuCode", "件号", true, "string", null, null)),
                "body", SkillAvailability.AVAILABLE, null);
        lenient().when(skillRegistry.get("test-skill")).thenReturn(skill);
    }

    private void registerPlugin(String id, String toolType, boolean enabled) {
        ToolProvider provider = org.mockito.Mockito.mock(ToolProvider.class);
        when(provider.name()).thenReturn(id);
        PluginDescriptor desc = new PluginDescriptor(
                id, toolType, id, "", "1.0",
                true, enabled, false, provider, null, null, null);
        registry.register(desc);
        if (!enabled) registry.disable(id);
    }

    @Test
    void shouldAcceptValidPluginOverrides() throws Exception {
        registerPlugin("remote-mysql", "mysql_query", true);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("skillId", "test-skill");
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("skuCode", "A001");
        body.put("inputs", inputs);
        body.put("pluginOverrides", Collections.singletonMap("mysql_query", "remote-mysql"));

        mockMvc.perform(post("/snap-agent/runs")
                        .contentType("application/json")
                        .content(toJson(body)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskId").exists());
    }

    @Test
    void shouldRejectOverrideWithUnknownPluginId() throws Exception {
        registerPlugin("mysql", "mysql_query", true);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("skillId", "test-skill");
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("skuCode", "A001");
        body.put("inputs", inputs);
        body.put("pluginOverrides", Collections.singletonMap("mysql_query", "nonexistent"));

        mockMvc.perform(post("/snap-agent/runs")
                        .contentType("application/json")
                        .content(toJson(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PLUGIN_OVERRIDE"));
    }

    @Test
    void shouldRejectOverrideWithDisabledPlugin() throws Exception {
        registerPlugin("custom-mysql", "mysql_query", false);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("skillId", "test-skill");
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("skuCode", "A001");
        body.put("inputs", inputs);
        body.put("pluginOverrides", Collections.singletonMap("mysql_query", "custom-mysql"));

        mockMvc.perform(post("/snap-agent/runs")
                        .contentType("application/json")
                        .content(toJson(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PLUGIN_OVERRIDE"));
    }

    @Test
    void shouldAcceptRunWithoutPluginOverrides() throws Exception {
        registerPlugin("mysql", "mysql_query", true);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("skillId", "test-skill");
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("skuCode", "A001");
        body.put("inputs", inputs);

        mockMvc.perform(post("/snap-agent/runs")
                        .contentType("application/json")
                        .content(toJson(body)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskId").exists());
    }

    private static String toJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(e.getKey()).append("\":");
            sb.append(toJsonValue(e.getValue()));
        }
        sb.append("}");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static String toJsonValue(Object v) {
        if (v instanceof String) return "\"" + v + "\"";
        if (v instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) v;
            return toJson(m);
        }
        return String.valueOf(v);
    }
}
