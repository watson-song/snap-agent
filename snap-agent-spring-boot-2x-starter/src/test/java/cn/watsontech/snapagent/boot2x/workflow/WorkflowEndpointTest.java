package cn.watsontech.snapagent.boot2x.workflow;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import cn.watsontech.snapagent.boot2x.web.SnapAgentController;
import cn.watsontech.snapagent.boot2x.workflow.YamlWorkflowLoader;
import cn.watsontech.snapagent.core.agent.RateLimiter;
import cn.watsontech.snapagent.core.agent.TaskStore;
import cn.watsontech.snapagent.core.security.SecurityGateway;
import cn.watsontech.snapagent.core.workflow.WorkflowDefinition;
import cn.watsontech.snapagent.core.workflow.WorkflowEngine;
import cn.watsontech.snapagent.core.workflow.WorkflowResult;
import cn.watsontech.snapagent.core.workflow.StepResult;
import cn.watsontech.snapagent.core.workflow.WorkflowStatus;
import cn.watsontech.snapagent.core.workflow.WorkflowStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for workflow REST endpoints in {@link SnapAgentController}.
 *
 * <p>Covers GET /workflows, GET /workflows/{name}, POST /workflows/{name}/run.
 * TDD spec: 07-workflow UC-R1..R3.</p>
 */
class WorkflowEndpointTest {

    private MockMvc mockMvc;
    private YamlWorkflowLoader workflowLoader;
    private WorkflowEngine workflowEngine;

    @BeforeEach
    void setUp() {
        workflowLoader = mock(YamlWorkflowLoader.class);
        workflowEngine = mock(WorkflowEngine.class);

        SecurityGateway securityGateway = mock(SecurityGateway.class);
        when(securityGateway.currentUserId()).thenReturn("test-user");
        when(securityGateway.hasPermission(anyString())).thenReturn(true);

        SnapAgentProperties props = new SnapAgentProperties();
        props.setEnabled(true);

        SnapAgentController controller = new SnapAgentController(
                null, null, mock(TaskStore.class), null, props, securityGateway,
                mock(RateLimiter.class), null, null, null, null, null,
                null, null, null, null, null, workflowLoader, workflowEngine,
                null, null, null, null);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private WorkflowDefinition sampleWorkflow(String name, String description, int stepCount) {
        List<WorkflowStep> steps = new java.util.ArrayList<WorkflowStep>();
        for (int i = 0; i < stepCount; i++) {
            steps.add(new WorkflowStep("step" + i, "skill" + i, null,
                    new HashMap<String, String>(), "SKIP"));
        }
        return new WorkflowDefinition(name, description, steps);
    }

    // ── GET /workflows ───────────────────────────────────────────

    @Test
    @DisplayName("GET /workflows returns list of available workflows")
    void shouldListWorkflows() throws Exception {
        when(workflowLoader.loadAll()).thenReturn(Arrays.asList(
                sampleWorkflow("deploy", "Deploy workflow", 3),
                sampleWorkflow("diagnose", "Diagnose workflow", 2)));

        mockMvc.perform(get("/snap-agent/workflows"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("deploy"))
                .andExpect(jsonPath("$[0].description").value("Deploy workflow"))
                .andExpect(jsonPath("$[0].stepCount").value(3))
                .andExpect(jsonPath("$[1].name").value("diagnose"))
                .andExpect(jsonPath("$[1].stepCount").value(2));
    }

    @Test
    @DisplayName("GET /workflows returns empty array when no workflows")
    void shouldReturnEmptyArrayWhenNoWorkflows() throws Exception {
        when(workflowLoader.loadAll()).thenReturn(Collections.<WorkflowDefinition>emptyList());

        mockMvc.perform(get("/snap-agent/workflows"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("GET /workflows returns 503 when workflow engine disabled")
    void shouldReturn503WhenWorkflowDisabled() throws Exception {
        SecurityGateway sg = mock(SecurityGateway.class);
        when(sg.currentUserId()).thenReturn("test-user");
        when(sg.hasPermission(anyString())).thenReturn(true);
        SnapAgentController disabledController = new SnapAgentController(
                null, null, mock(TaskStore.class), null, new SnapAgentProperties(), sg,
                mock(RateLimiter.class), null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null);
        MockMvc disabledMvc = MockMvcBuilders.standaloneSetup(disabledController).build();

        disabledMvc.perform(get("/snap-agent/workflows"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("WORKFLOWS_DISABLED"));
    }

    // ── GET /workflows/{name} ────────────────────────────────────

    @Test
    @DisplayName("GET /workflows/{name} returns workflow definition")
    void shouldGetWorkflow() throws Exception {
        WorkflowDefinition wf = sampleWorkflow("deploy", "Deploy workflow", 2);
        when(workflowLoader.load("deploy")).thenReturn(wf);

        mockMvc.perform(get("/snap-agent/workflows/deploy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("deploy"))
                .andExpect(jsonPath("$.description").value("Deploy workflow"))
                .andExpect(jsonPath("$.steps.length()").value(2))
                .andExpect(jsonPath("$.steps[0].name").value("step0"))
                .andExpect(jsonPath("$.steps[0].skill").value("skill0"))
                .andExpect(jsonPath("$.steps[0].onFailure").value("SKIP"));
    }

    @Test
    @DisplayName("GET /workflows/{name} returns 404 when not found")
    void shouldReturn404WhenWorkflowNotFound() throws Exception {
        when(workflowLoader.load("nonexistent")).thenReturn(null);

        mockMvc.perform(get("/snap-agent/workflows/nonexistent"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("WORKFLOW_NOT_FOUND"));
    }

    // ── POST /workflows/{name}/run ───────────────────────────────

    @Test
    @DisplayName("POST /workflows/{name}/run executes workflow and returns result")
    void shouldRunWorkflow() throws Exception {
        WorkflowDefinition wf = sampleWorkflow("deploy", "Deploy workflow", 2);
        when(workflowLoader.load("deploy")).thenReturn(wf);

        Map<String, StepResult> stepResults = new LinkedHashMap<String, StepResult>();
        stepResults.put("step0", new StepResult("step0", "task-1", "SUCCEEDED", "done"));
        stepResults.put("step1", new StepResult("step1", "task-2", "SUCCEEDED", "done2"));

        WorkflowResult result = WorkflowResult.success("deploy", stepResults, 500L);
        when(workflowEngine.execute(any(WorkflowDefinition.class), any(Map.class)))
                .thenReturn(result);

        mockMvc.perform(post("/snap-agent/workflows/deploy/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"env\":\"prod\",\"version\":\"1.0\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowName").value("deploy"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.durationMs").value(500))
                .andExpect(jsonPath("$.stepResults.step0.taskId").value("task-1"))
                .andExpect(jsonPath("$.stepResults.step0.status").value("SUCCEEDED"));
    }

    @Test
    @DisplayName("POST /workflows/{name}/run returns 404 when workflow not found")
    void shouldReturn404WhenRunWorkflowNotFound() throws Exception {
        when(workflowLoader.load("nonexistent")).thenReturn(null);

        mockMvc.perform(post("/snap-agent/workflows/nonexistent/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("WORKFLOW_NOT_FOUND"));
    }

    @Test
    @DisplayName("POST /workflows/{name}/run returns 503 when workflow engine disabled")
    void shouldReturn503WhenRunWorkflowDisabled() throws Exception {
        SecurityGateway sg = mock(SecurityGateway.class);
        when(sg.currentUserId()).thenReturn("test-user");
        when(sg.hasPermission(anyString())).thenReturn(true);
        SnapAgentController disabledController = new SnapAgentController(
                null, null, mock(TaskStore.class), null, new SnapAgentProperties(), sg,
                mock(RateLimiter.class), null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null);
        MockMvc disabledMvc = MockMvcBuilders.standaloneSetup(disabledController).build();

        disabledMvc.perform(post("/snap-agent/workflows/deploy/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("WORKFLOWS_DISABLED"));
    }

    @Test
    @DisplayName("POST /workflows/{name}/run with missing body returns 400")
    void shouldReturn400WhenRunWorkflowMissingInputs() throws Exception {
        when(workflowLoader.load("deploy")).thenReturn(
                sampleWorkflow("deploy", "Deploy workflow", 1));

        mockMvc.perform(post("/snap-agent/workflows/deploy/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(""))
                .andExpect(status().isBadRequest());
    }
}
