package cn.watsontech.snapagent.boot2x.cost;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import cn.watsontech.snapagent.boot2x.web.SnapAgentController;
import cn.watsontech.snapagent.core.agent.RateLimiter;
import cn.watsontech.snapagent.core.agent.TaskStore;
import cn.watsontech.snapagent.core.cost.CostRecord;
import cn.watsontech.snapagent.core.cost.CostStore;
import cn.watsontech.snapagent.core.cost.CostSummary;
import cn.watsontech.snapagent.core.security.SecurityGateway;
import cn.watsontech.snapagent.core.skill.InputSpec;
import cn.watsontech.snapagent.core.skill.SkillAvailability;
import cn.watsontech.snapagent.core.skill.SkillMeta;
import cn.watsontech.snapagent.core.skill.SkillRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for cost accounting REST endpoints in {@link SnapAgentController}.
 *
 * <p>Covers GET /cost/summary, GET /cost/users/{userId}/summary,
 * GET /cost/skills/{skillName}/summary, GET /cost/records.
 * TDD spec: 10-cost-security UC-R1..R4, E2E-5..E2E-7.</p>
 */
@ExtendWith(MockitoExtension.class)
class CostEndpointTest {

    @Mock private CostSummaryService costSummaryService;
    @Mock private CostStore costStore;
    @Mock private SecurityGateway securityGateway;
    @Mock private RateLimiter rateLimiter;
    @Mock private TaskStore taskStore;
    @Mock private SkillRegistry skillRegistry;

    private MockMvc mockMvc;
    private SnapAgentProperties props;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        props = new SnapAgentProperties();
        props.setEnabled(true);
        objectMapper = new ObjectMapper();

        lenient().when(costSummaryService.getStore()).thenReturn(costStore);
        lenient().when(securityGateway.currentUserId()).thenReturn("test-user");
        lenient().when(securityGateway.hasPermission(anyString())).thenReturn(true);

        SnapAgentController controller = new SnapAgentController(
                null, null, taskStore, null, props, securityGateway,
                rateLimiter, null, null, null, null, null,
                null, null, null, null, costSummaryService, null, null, null, null, null, null);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private CostSummary sampleSummary(String dimension, String dimensionValue) {
        return new CostSummary(dimension, dimensionValue,
                new BigDecimal("1.25"), 5000, 3000, 10,
                new BigDecimal("100.00"), 0.0125);
    }

    // ── GET /cost/summary ─────────────────────────────────────────

    @Test
    @DisplayName("GET /cost/summary returns global summary by default")
    void shouldReturnGlobalCostSummary() throws Exception {
        when(costSummaryService.getGlobalSummary(1000L, 2000L))
                .thenReturn(sampleSummary("global", "global"));

        mockMvc.perform(get("/snap-agent/cost/summary?from=1000&to=2000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dimension").value("global"))
                .andExpect(jsonPath("$.totalCost").value("1.25"))
                .andExpect(jsonPath("$.totalInputTokens").value(5000))
                .andExpect(jsonPath("$.totalOutputTokens").value(3000))
                .andExpect(jsonPath("$.requestCount").value(10));
    }

    @Test
    @DisplayName("GET /cost/summary?groupBy=user returns user dimension")
    void shouldReturnUserGroupBySummary() throws Exception {
        when(costSummaryService.getGlobalSummary(1000L, 2000L))
                .thenReturn(sampleSummary("user", "global"));

        mockMvc.perform(get("/snap-agent/cost/summary?from=1000&to=2000&groupBy=user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dimension").value("user"));
    }

    @Test
    @DisplayName("GET /cost/summary?groupBy=skill returns skill dimension")
    void shouldReturnSkillGroupBySummary() throws Exception {
        when(costSummaryService.getGlobalSummary(1000L, 2000L))
                .thenReturn(sampleSummary("skill", "global"));

        mockMvc.perform(get("/snap-agent/cost/summary?from=1000&to=2000&groupBy=skill"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dimension").value("skill"));
    }

    @Test
    @DisplayName("GET /cost/summary returns 503 when cost disabled")
    void shouldReturn503WhenCostDisabled() throws Exception {
        SecurityGateway sg = mock(SecurityGateway.class);
        when(sg.currentUserId()).thenReturn("test-user");
        when(sg.hasPermission(anyString())).thenReturn(true);
        SnapAgentController disabledController = new SnapAgentController(
                null, null, taskStore, null, new SnapAgentProperties(), sg,
                rateLimiter, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null);
        MockMvc disabledMvc = MockMvcBuilders.standaloneSetup(disabledController).build();

        disabledMvc.perform(get("/snap-agent/cost/summary?from=1000&to=2000"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("COST_DISABLED"));
    }

    // ── GET /cost/users/{userId}/summary ─────────────────────────

    @Test
    @DisplayName("GET /cost/users/{userId}/summary returns user summary")
    void shouldReturnUserCostSummary() throws Exception {
        when(costSummaryService.getUserSummary("alice", 1000L, 2000L))
                .thenReturn(sampleSummary("user", "alice"));

        mockMvc.perform(get("/snap-agent/cost/users/alice/summary?from=1000&to=2000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dimensionValue").value("alice"))
                .andExpect(jsonPath("$.totalCost").value("1.25"));
    }

    // ── GET /cost/skills/{skillName}/summary ─────────────────────

    @Test
    @DisplayName("GET /cost/skills/{skillName}/summary returns skill summary")
    void shouldReturnSkillCostSummary() throws Exception {
        when(costSummaryService.getSkillSummary("health-patrol", 1000L, 2000L))
                .thenReturn(sampleSummary("skill", "health-patrol"));

        mockMvc.perform(get("/snap-agent/cost/skills/health-patrol/summary?from=1000&to=2000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dimensionValue").value("health-patrol"));
    }

    // ── GET /cost/records ─────────────────────────────────────────

    @Test
    @DisplayName("GET /cost/records returns all records when no filter")
    void shouldListAllCostRecords() throws Exception {
        when(costSummaryService.listRecords(1000L, 2000L)).thenReturn(Arrays.asList(
                new CostRecord("r1", "alice", "health-patrol", null, "claude-sonnet-4-6",
                        100, 50, 0, new BigDecimal("0.05"), 1000L),
                new CostRecord("r2", "bob", "code-review", null, "claude-sonnet-4-6",
                        200, 100, 0, new BigDecimal("0.10"), 2000L)));

        mockMvc.perform(get("/snap-agent/cost/records?from=1000&to=2000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records.length()").value(2))
                .andExpect(jsonPath("$.total").value(2));
    }

    @Test
    @DisplayName("GET /cost/records?userId=alice filters by user")
    void shouldFilterCostRecordsByUser() throws Exception {
        when(costStore.listByUser("alice", 1000L, 2000L)).thenReturn(Collections.singletonList(
                new CostRecord("r1", "alice", "health-patrol", null, "claude-sonnet-4-6",
                        100, 50, 0, new BigDecimal("0.05"), 1000L)));

        mockMvc.perform(get("/snap-agent/cost/records?from=1000&to=2000&userId=alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records.length()").value(1))
                .andExpect(jsonPath("$.records[0].userId").value("alice"));
    }

    @Test
    @DisplayName("GET /cost/records?skillName=health-patrol filters by skill")
    void shouldFilterCostRecordsBySkill() throws Exception {
        when(costStore.listBySkill("health-patrol", 1000L, 2000L)).thenReturn(Collections.singletonList(
                new CostRecord("r1", "alice", "health-patrol", null, "claude-sonnet-4-6",
                        100, 50, 0, new BigDecimal("0.05"), 1000L)));

        mockMvc.perform(get("/snap-agent/cost/records?from=1000&to=2000&skillName=health-patrol"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records.length()").value(1))
                .andExpect(jsonPath("$.records[0].skillName").value("health-patrol"));
    }

    // ── E2E-5: GET /cost/summary with no auth → 401 ──────────────

    @Test
    @DisplayName("E2E-5: GET /cost/summary returns 401 when not authenticated")
    void shouldReturn401WhenNoAuth() throws Exception {
        SecurityGateway sg = mock(SecurityGateway.class);
        when(sg.currentUserId()).thenReturn(null);
        SnapAgentController unauthController = new SnapAgentController(
                null, null, taskStore, null, props, sg,
                rateLimiter, null, null, null, null, null,
                null, null, null, null, costSummaryService, null, null, null, null, null, null);
        MockMvc unauthMvc = MockMvcBuilders.standaloneSetup(unauthController).build();

        unauthMvc.perform(get("/snap-agent/cost/summary?from=1000&to=2000"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    // ── E2E-6: GET /cost/summary without permission → 403 ────────

    @Test
    @DisplayName("E2E-6: GET /cost/summary returns 403 without cost:query permission")
    void shouldReturn403WithoutPermission() throws Exception {
        SecurityGateway sg = mock(SecurityGateway.class);
        when(sg.currentUserId()).thenReturn("test-user");
        when(sg.hasPermission(anyString())).thenReturn(false);
        SnapAgentController forbiddenController = new SnapAgentController(
                null, null, taskStore, null, props, sg,
                rateLimiter, null, null, null, null, null,
                null, null, null, null, costSummaryService, null, null, null, null, null, null);
        MockMvc forbiddenMvc = MockMvcBuilders.standaloneSetup(forbiddenController).build();

        forbiddenMvc.perform(get("/snap-agent/cost/summary?from=1000&to=2000"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    // ── E2E-7: POST /runs rate limit → 429 ───────────────────────

    @Test
    @DisplayName("E2E-7: POST /runs returns 429 when rate limit exceeded")
    void shouldReturn429WhenRateLimited() throws Exception {
        SkillMeta skill = new SkillMeta("test-skill", "测试",
                Collections.singletonList("mysql_query"), Collections.<InputSpec>emptyList(),
                "body", SkillAvailability.AVAILABLE, null);
        when(skillRegistry.get("test-skill")).thenReturn(skill);
        when(rateLimiter.tryAcquire("test-user")).thenReturn(false);

        SnapAgentController runController = new SnapAgentController(
                skillRegistry, null, taskStore, null, props, securityGateway,
                rateLimiter, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null);
        MockMvc runMvc = MockMvcBuilders.standaloneSetup(runController).build();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("skillId", "test-skill");

        runMvc.perform(post("/snap-agent/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("RATE_LIMITED"));
    }
}
