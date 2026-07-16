package cn.watsontech.snapagent.boot2x.closure;

import cn.watsontech.snapagent.core.agent.AgentExecutor;
import cn.watsontech.snapagent.core.agent.AgentTask;
import cn.watsontech.snapagent.core.agent.TaskStore;
import cn.watsontech.snapagent.core.agent.TaskStatus;
import cn.watsontech.snapagent.core.closure.IssueClosure;
import cn.watsontech.snapagent.core.closure.VerificationResult;
import cn.watsontech.snapagent.core.skill.SkillAvailability;
import cn.watsontech.snapagent.core.skill.SkillMeta;
import cn.watsontech.snapagent.core.skill.SkillRegistry;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SimpleVerificationRunnerTest {

    @Test
    void verify_nullTaskId_returnsFailed() {
        SimpleVerificationRunner runner = new SimpleVerificationRunner(
                mock(TaskStore.class), mock(AgentExecutor.class), mock(SkillRegistry.class));

        IssueClosure issue = new IssueClosure();
        // taskId is null
        VerificationResult result = runner.verify(issue);

        assertThat(result.isPassed()).isFalse();
        assertThat(result.getSummary()).contains("缺少原始任务");
    }

    @Test
    void verify_taskNotFound_returnsFailed() {
        TaskStore taskStore = mock(TaskStore.class);
        when(taskStore.get("nonexistent")).thenReturn(null);

        SimpleVerificationRunner runner = new SimpleVerificationRunner(
                taskStore, mock(AgentExecutor.class), mock(SkillRegistry.class));

        IssueClosure issue = new IssueClosure();
        issue.setTaskId("nonexistent");

        VerificationResult result = runner.verify(issue);

        assertThat(result.isPassed()).isFalse();
        assertThat(result.getSummary()).contains("原始任务不存在");
    }

    @Test
    void verify_skillNotFound_returnsFailed() {
        TaskStore taskStore = mock(TaskStore.class);
        SkillRegistry skillRegistry = mock(SkillRegistry.class);
        AgentTask task = mock(AgentTask.class);
        when(task.getUserId()).thenReturn("user1");
        when(task.getSkillId()).thenReturn("nonexistent-skill");
        when(task.getInputs()).thenReturn(new java.util.HashMap<String, String>());
        when(task.getModel()).thenReturn("test-model");
        when(taskStore.get("task-1")).thenReturn(task);
        when(skillRegistry.get("nonexistent-skill")).thenReturn(null);

        SimpleVerificationRunner runner = new SimpleVerificationRunner(
                taskStore, mock(AgentExecutor.class), skillRegistry);

        IssueClosure issue = new IssueClosure();
        issue.setTaskId("task-1");
        issue.setSkillName("nonexistent-skill");

        VerificationResult result = runner.verify(issue);

        assertThat(result.isPassed()).isFalse();
        assertThat(result.getSummary()).contains("skill 不存在");
    }

    @Test
    void verify_executorThrows_returnsFailedWithError() {
        TaskStore taskStore = mock(TaskStore.class);
        SkillRegistry skillRegistry = mock(SkillRegistry.class);
        AgentExecutor executor = mock(AgentExecutor.class);
        AgentTask task = mock(AgentTask.class);
        SkillMeta skill = new SkillMeta("test-skill", "test description",
                Collections.singletonList("mysql_query"), Collections.emptyList(),
                "## Phase 1\ntest body", SkillAvailability.AVAILABLE, null);

        when(task.getUserId()).thenReturn("user1");
        when(task.getSkillId()).thenReturn("test-skill");
        when(task.getInputs()).thenReturn(new java.util.HashMap<String, String>());
        when(task.getModel()).thenReturn("test-model");
        when(taskStore.get("task-1")).thenReturn(task);
        when(skillRegistry.get("test-skill")).thenReturn(skill);
        when(task.getStatus()).thenReturn(TaskStatus.SUCCEEDED);

        // Simulate executor throwing
        org.mockito.Mockito.doThrow(new RuntimeException("LLM error"))
                .when(executor).execute(org.mockito.ArgumentMatchers.any(AgentTask.class),
                        org.mockito.ArgumentMatchers.eq(skill));

        SimpleVerificationRunner runner = new SimpleVerificationRunner(
                taskStore, executor, skillRegistry);

        IssueClosure issue = new IssueClosure();
        issue.setTaskId("task-1");
        issue.setSkillName("test-skill");

        VerificationResult result = runner.verify(issue);

        assertThat(result.isPassed()).isFalse();
        assertThat(result.getSummary()).contains("验证异常");
        assertThat(result.getAfterStatus()).isEqualTo("ERROR");
    }
}
