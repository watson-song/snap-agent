package cn.watsontech.snapagent.boot2x.autoconfig;

import cn.watsontech.snapagent.boot2x.agent.AgentService;
import cn.watsontech.snapagent.boot2x.fix.FileEditTool;
import cn.watsontech.snapagent.boot2x.fix.FileWriteTool;
import cn.watsontech.snapagent.boot2x.fix.FixContextHolder;
import cn.watsontech.snapagent.boot2x.fix.FixExecutionService;
import cn.watsontech.snapagent.boot2x.fix.FixGuard;
import cn.watsontech.snapagent.boot2x.issue.FileIssueStore;
import cn.watsontech.snapagent.boot2x.issue.GitHubIssueTracker;
import cn.watsontech.snapagent.boot2x.issue.IssueClosureService;
import cn.watsontech.snapagent.boot2x.issue.JiraIssueTracker;
import cn.watsontech.snapagent.boot2x.issue.NoopIssueTracker;
import cn.watsontech.snapagent.boot2x.issue.SimpleVerificationRunner;
import cn.watsontech.snapagent.boot2x.issue.TemplateSolutionSuggester;
import cn.watsontech.snapagent.boot2x.issue.ZentaoIssueTracker;
import cn.watsontech.snapagent.boot2x.knowledge.KnowledgeSedimentationService;
import cn.watsontech.snapagent.boot2x.vcs.BitbucketVcsClient;
import cn.watsontech.snapagent.boot2x.vcs.GitLabVcsClient;
import cn.watsontech.snapagent.core.agent.TaskStore;
import cn.watsontech.snapagent.core.embedding.EmbeddingModel;
import cn.watsontech.snapagent.core.issue.IssueStore;
import cn.watsontech.snapagent.core.issue.IssueTracker;
import cn.watsontech.snapagent.core.issue.SolutionSuggester;
import cn.watsontech.snapagent.core.issue.VerificationRunner;
import cn.watsontech.snapagent.core.skill.SkillRegistry;
import cn.watsontech.snapagent.core.vectorstore.VectorStore;
import cn.watsontech.snapagent.core.vcs.VcsClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration for issue-tracking, VCS-client, and auto-fix beans.
 *
 * <p>Extracted from {@link SnapAgentAutoConfiguration} for clarity. Activated only
 * when {@code snap-agent.enabled=true} (default false). Individual beans carry
 * their own {@code @ConditionalOnProperty} annotations for sub-features such as
 * {@code snap-agent.issue-closure.enabled}, {@code snap-agent.vcs.type}, and
 * {@code snap-agent.fix.enabled}.</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "snap-agent", name = "enabled", havingValue = "true")
public class IssueAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(IssueAutoConfiguration.class);

    // ---- helpers ----

    // ---- Issue Closure (v0.9) ----

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "snap-agent.issue-closure", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(IssueStore.class)
    public FileIssueStore fileIssueStore(SnapAgentProperties props) {
        String storageDir = props.getIssueClosure().getStorageDir();
        if (storageDir == null || storageDir.isEmpty()) {
            storageDir = props.getUploadSkillsDir() + "/issues";
        }
        log.info("FileIssueStore assembled with storage dir: {}", storageDir);
        return new FileIssueStore(storageDir);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "snap-agent.issue-closure", name = "tracker-type",
            havingValue = "noop", matchIfMissing = true)
    public NoopIssueTracker noopIssueTracker() {
        log.info("NoopIssueTracker assembled (tracker-type=noop)");
        return new NoopIssueTracker();
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "snap-agent.issue-closure", name = "tracker-type", havingValue = "zentao")
    public ZentaoIssueTracker zentaoIssueTracker(SnapAgentProperties props) {
        SnapAgentProperties.IssueClosure.ZentaoTracker zt = props.getIssueClosure().getZentao();
        log.info("ZentaoIssueTracker assembled (base-url={}, product-id={})",
                zt.getBaseUrl(), zt.getProductId());
        return new ZentaoIssueTracker(zt);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "snap-agent.issue-closure", name = "tracker-type", havingValue = "github")
    public GitHubIssueTracker gitHubIssueTracker(SnapAgentProperties props) {
        SnapAgentProperties.IssueClosure.GitHubTracker gh = props.getIssueClosure().getGithub();
        log.info("GitHubIssueTracker assembled (owner={}, repo={})",
                gh.getOwner(), gh.getRepo());
        return new GitHubIssueTracker(gh);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "snap-agent.issue-closure", name = "tracker-type", havingValue = "jira")
    public JiraIssueTracker jiraIssueTracker(SnapAgentProperties props) {
        SnapAgentProperties.IssueClosure.JiraTracker jr = props.getIssueClosure().getJira();
        log.info("JiraIssueTracker assembled (base-url={}, project-key={})",
                jr.getBaseUrl(), jr.getProjectKey());
        return new JiraIssueTracker(jr);
    }

    // ---- VCS Client (v1.1 auto-fix) ----

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "snap-agent.vcs", name = "type", havingValue = "gitlab", matchIfMissing = true)
    @ConditionalOnMissingBean(VcsClient.class)
    public GitLabVcsClient gitLabVcsClient(SnapAgentProperties props) {
        SnapAgentProperties.Vcs.GitLab gl = props.getVcs().getGitlab();
        log.info("GitLabVcsClient assembled (base-url={}, project-id={})",
                gl.getBaseUrl(), gl.getProjectId());
        return new GitLabVcsClient(gl);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "snap-agent.vcs", name = "type", havingValue = "bitbucket")
    @ConditionalOnMissingBean(VcsClient.class)
    public BitbucketVcsClient bitbucketVcsClient(SnapAgentProperties props) {
        SnapAgentProperties.Vcs.Bitbucket bb = props.getVcs().getBitbucket();
        log.info("BitbucketVcsClient assembled (base-url={}, project={}, repo={})",
                bb.getBaseUrl(), bb.getProjectKey(), bb.getRepoSlug());
        return new BitbucketVcsClient(bb);
    }

    // ---- Fix infrastructure (v1.1 auto-fix) ----

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "snap-agent.fix", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public FixContextHolder fixContextHolder() {
        log.info("FixContextHolder assembled");
        return new FixContextHolder();
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "snap-agent.fix", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public FixGuard fixGuard(SnapAgentProperties props) {
        log.info("FixGuard assembled (include-paths={}, exclude-paths={})",
                props.getFix().getGuard().getIncludePaths().size(),
                props.getFix().getGuard().getExcludePaths().size());
        return new FixGuard(props.getFix().getGuard());
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "snap-agent.fix", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public FileWriteTool fileWriteTool(FixContextHolder holder, FixGuard guard) {
        log.info("FileWriteTool assembled");
        return new FileWriteTool(holder, guard);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "snap-agent.fix", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public FileEditTool fileEditTool(FixContextHolder holder, FixGuard guard) {
        log.info("FileEditTool assembled");
        return new FileEditTool(holder, guard);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "snap-agent.fix", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public FixExecutionService fixExecutionService(
            AgentService agentService,
            IssueStore issueStore,
            SkillRegistry skillRegistry,
            ObjectProvider<VcsClient> vcsClientProvider,
            FixContextHolder fixContextHolder,
            SnapAgentProperties props) {
        log.info("FixExecutionService assembled (max-turns={})", props.getFix().getMaxTurns());
        return new FixExecutionService(agentService, issueStore, skillRegistry,
                vcsClientProvider.getIfAvailable(), fixContextHolder,
                props.getFix().getProjectRoot(),
                props.getIssueClosure().getSystemUserId(),
                props.getVcs().getDefaultBranch());
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "snap-agent.issue-closure", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public KnowledgeSedimentationService knowledgeSedimentationService(
            ObjectProvider<VectorStore> vectorStoreProvider,
            ObjectProvider<EmbeddingModel> embeddingModelProvider) {
        log.info("KnowledgeSedimentationService assembled");
        return new KnowledgeSedimentationService(
                vectorStoreProvider.getIfAvailable(),
                embeddingModelProvider.getIfAvailable());
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "snap-agent.issue-closure", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(SolutionSuggester.class)
    public TemplateSolutionSuggester templateSolutionSuggester() {
        log.info("TemplateSolutionSuggester assembled");
        return new TemplateSolutionSuggester();
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "snap-agent.issue-closure", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(VerificationRunner.class)
    public SimpleVerificationRunner simpleVerificationRunner(
            AgentService agentService,
            TaskStore taskStore,
            SkillRegistry skillRegistry,
            SnapAgentProperties properties) {
        log.info("SimpleVerificationRunner assembled (system-user-id={})",
                properties.getIssueClosure().getSystemUserId());
        return new SimpleVerificationRunner(agentService, taskStore, skillRegistry,
                properties.getIssueClosure().getSystemUserId());
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "snap-agent.issue-closure", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public IssueClosureService issueClosureService(
            AgentService agentService,
            TaskStore taskStore,
            SkillRegistry skillRegistry,
            IssueStore issueStore,
            IssueTracker issueTracker,
            ObjectProvider<KnowledgeSedimentationService> sedimentationServiceProvider,
            ObjectProvider<SolutionSuggester> solutionSuggesterProvider,
            ObjectProvider<VerificationRunner> verificationRunnerProvider,
            ObjectProvider<FixExecutionService> fixExecutionServiceProvider,
            SnapAgentProperties properties) {
        log.info("IssueClosureService assembled (system-user-id={})",
                properties.getIssueClosure().getSystemUserId());
        return new IssueClosureService(agentService, taskStore, skillRegistry,
                issueStore, issueTracker,
                sedimentationServiceProvider.getIfAvailable(),
                solutionSuggesterProvider.getIfAvailable(),
                verificationRunnerProvider.getIfAvailable(),
                properties.getIssueClosure().getSystemUserId(),
                fixExecutionServiceProvider.getIfAvailable());
    }
}
