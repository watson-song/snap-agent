package cn.watsontech.snapagent.boot2x.autoconfig;

import cn.watsontech.snapagent.boot2x.tool.ToolPluginRegistry;
import cn.watsontech.snapagent.boot2x.workflow.SimpleWorkflowEngine;
import cn.watsontech.snapagent.boot2x.workflow.YamlWorkflowLoader;
import cn.watsontech.snapagent.boot2x.agent.AgentService;
import cn.watsontech.snapagent.boot2x.workflow.WorkflowEngine;
import cn.watsontech.snapagent.core.skill.SkillRegistry;
import cn.watsontech.snapagent.core.tool.ToolPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Workflow engine auto-configuration — YAML workflow loader and
 * simple workflow engine.
 *
 * <p>Tool plugin registry is always active (not gated by a property).
 * Workflow loader and engine are active only when
 * {@code snap-agent.workflows.enabled=true}.</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "snap-agent", name = "enabled", havingValue = "true")
public class WorkflowAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(WorkflowAutoConfiguration.class);

    // ---- Tool Plugin Registry (v1.0) ----

    @Bean
    @ConditionalOnMissingBean
    public ToolPluginRegistry toolPluginRegistry(ObjectProvider<ToolPlugin> toolPluginProvider) {
        java.util.List<ToolPlugin> plugins = toolPluginProvider.orderedStream()
                .collect(java.util.stream.Collectors.toList());
        log.info("ToolPluginRegistry assembled with {} plugin(s)", plugins.size());
        return new ToolPluginRegistry(plugins);
    }

    // ---- Workflow Engine (v1.0) ----

    @Bean
    @ConditionalOnProperty(prefix = "snap-agent.workflows", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public YamlWorkflowLoader yamlWorkflowLoader(SnapAgentProperties props) {
        String dir = props.getWorkflows().getDir();
        Path workflowsDir;
        if (dir == null || dir.isEmpty()) {
            workflowsDir = Paths.get(props.getUploadSkillsDir()).resolve("workflows");
        } else {
            String path = dir;
            if (path.startsWith("file:")) {
                path = path.substring(5);
            }
            workflowsDir = Paths.get(path);
        }
        try {
            if (!Files.isDirectory(workflowsDir)) {
                Files.createDirectories(workflowsDir);
                log.info("Created workflows dir: {}", workflowsDir);
            }
        } catch (java.io.IOException e) {
            log.warn("Failed to create workflows dir {}: {}", workflowsDir, e.getMessage());
        }
        log.info("YamlWorkflowLoader assembled with dir: {}", workflowsDir);
        return new YamlWorkflowLoader(workflowsDir);
    }

    @Bean
    @ConditionalOnProperty(prefix = "snap-agent.workflows", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(WorkflowEngine.class)
    public SimpleWorkflowEngine simpleWorkflowEngine(
            AgentService agentService,
            SkillRegistry skillRegistry,
            SnapAgentProperties props) {
        String systemUserId = props.getIssueClosure().getSystemUserId();
        log.info("SimpleWorkflowEngine assembled (systemUserId={})", systemUserId);
        return new SimpleWorkflowEngine(agentService, skillRegistry, systemUserId);
    }
}
