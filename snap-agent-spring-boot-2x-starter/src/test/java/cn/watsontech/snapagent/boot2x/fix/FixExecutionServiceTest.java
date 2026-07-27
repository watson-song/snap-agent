package cn.watsontech.snapagent.boot2x.fix;

import cn.watsontech.snapagent.boot2x.agent.AgentService;
import cn.watsontech.snapagent.core.agent.AgentTask;
import cn.watsontech.snapagent.core.issue.IssueClosure;
import cn.watsontech.snapagent.core.issue.IssueStatus;
import cn.watsontech.snapagent.core.issue.IssueStore;
import cn.watsontech.snapagent.core.skill.SkillMeta;
import cn.watsontech.snapagent.core.skill.SkillRegistry;
import cn.watsontech.snapagent.core.vcs.FileChange;
import cn.watsontech.snapagent.core.vcs.FixResult;
import cn.watsontech.snapagent.core.vcs.MergeRequestInfo;
import cn.watsontech.snapagent.core.vcs.VcsClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class FixExecutionServiceTest {

    private AgentService agentService;
    private IssueStore issueStore;
    private SkillRegistry skillRegistry;
    private VcsClient vcsClient;
    private FixContextHolder fixContextHolder;
    private FixExecutionService service;

    @BeforeEach
    void setUp() {
        agentService = mock(AgentService.class);
        issueStore = mock(IssueStore.class);
        skillRegistry = mock(SkillRegistry.class);
        vcsClient = mock(VcsClient.class);
        fixContextHolder = new FixContextHolder();
        service = new FixExecutionService(agentService, issueStore, skillRegistry,
                vcsClient, fixContextHolder, "/tmp", "system", "main");

        SkillMeta skill = new SkillMeta("code-analysis", "code analysis",
                new java.util.ArrayList<java.lang.String>(),
                new java.util.ArrayList<cn.watsontech.snapagent.core.skill.InputSpec>(),
                new java.util.ArrayList<cn.watsontech.snapagent.core.skill.Shortcut>(),
                null, cn.watsontech.snapagent.core.skill.SkillAvailability.AVAILABLE, null,
                "custom", false, "");
        when(skillRegistry.get("code-analysis")).thenReturn(skill);
    }

    @Test
    void autoFix_returnsFailedWhenIssueNotFound() {
        when(issueStore.load("nope")).thenReturn(null);
        FixResult result = service.autoFix("nope");
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("Issue not found");
    }

    @Test
    void autoFix_returnsFailedWhenNoChangesProduced() {
        IssueClosure issue = new IssueClosure(
                "i1", "EXT-1", "t1", null, "u", "q", "rc",
                null, null, IssueStatus.FIX_IN_PROGRESS,
                null, null, null, null, null, 1L, 1L);
        when(issueStore.load("i1")).thenReturn(issue);

        // Agent runs but FixContext has no changes (no file tools invoked)
        doAnswer(inv -> {
            // Simulate agent execution — don't add any changes
            return null;
        }).when(agentService).execute(any(AgentTask.class), any(SkillMeta.class));

        FixResult result = service.autoFix("i1");
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("code changes");
    }

    @Test
    void autoFix_succeedsWhenChangesProduced() {
        IssueClosure issue = new IssueClosure(
                "i2", "EXT-2", "t2", null, "u", "q", "rc",
                null, null, IssueStatus.FIX_IN_PROGRESS,
                null, null, null, null, null, 1L, 1L);
        when(issueStore.load("i2")).thenReturn(issue);

        doAnswer(inv -> {
            FixContext ctx = fixContextHolder.get();
            if (ctx != null) {
                ctx.addChange("src/main/java/Foo.java", "public class Foo {}", "CREATE");
            }
            return null;
        }).when(agentService).execute(any(AgentTask.class), any(SkillMeta.class));

        doNothing().when(vcsClient).createBranch(anyString());
        when(vcsClient.commitFiles(anyString(), anyList(), anyString())).thenReturn("sha123");
        when(vcsClient.createPullRequest(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new MergeRequestInfo("https://gitlab/mr/1", "1", "sha123"));

        FixResult result = service.autoFix("i2");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getCommitId()).isEqualTo("sha123");
        assertThat(result.getPrUrl()).isEqualTo("https://gitlab/mr/1");
        assertThat(result.getPrNumber()).isEqualTo("1");
        assertThat(result.getChangedFiles()).containsExactly("src/main/java/Foo.java");

        verify(vcsClient).createBranch("fix/i2");
        verify(vcsClient).commitFiles(eq("fix/i2"), anyList(), anyString());
        verify(vcsClient).createPullRequest(eq("fix/i2"), eq("main"), anyString(), anyString());
    }

    @Test
    void autoFix_catchesVcsExceptions() {
        IssueClosure issue = new IssueClosure(
                "i3", null, "t3", null, "u", "q", "rc",
                null, null, IssueStatus.FIX_IN_PROGRESS,
                null, null, null, null, null, 1L, 1L);
        when(issueStore.load("i3")).thenReturn(issue);

        doAnswer(inv -> {
            FixContext ctx = fixContextHolder.get();
            if (ctx != null) {
                ctx.addChange("src/Foo.java", "content", "CREATE");
            }
            return null;
        }).when(agentService).execute(any(AgentTask.class), any(SkillMeta.class));

        doThrow(new RuntimeException("branch creation failed")).when(vcsClient).createBranch(anyString());

        FixResult result = service.autoFix("i3");
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("branch creation failed");
    }
}
