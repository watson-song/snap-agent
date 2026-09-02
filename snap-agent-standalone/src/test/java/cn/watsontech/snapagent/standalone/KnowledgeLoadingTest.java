package cn.watsontech.snapagent.standalone;

import org.junit.jupiter.api.Test;

import java.net.URL;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证全部知识库文件正确加载到 classpath
 */
class KnowledgeLoadingTest {

    private static final String[] EXPECTED_FILES = {
        "snap-agent-architecture.md",
        "snap-agent-agent-engine.md",
        "snap-agent-advisor-system.md",
        "snap-agent-tool-system.md",
        "snap-agent-graph-engine.md",
        "snap-agent-memory-system.md",
        "snap-agent-skill-system.md",
        "snap-agent-bridge-system.md",
        "snap-agent-code-graph.md",
        "snap-agent-rag-system.md",
        "snap-agent-domain-knowledge.md",
        "snap-agent-security-system.md",
        "snap-agent-cost-tracking.md",
        "snap-agent-metrics.md",
        "snap-agent-llm-client.md",
        "snap-agent-patrol-system.md",
        "snap-agent-issue-tracker.md",
        "snap-agent-conversation.md",
        "snap-agent-workflow.md",
        "snap-agent-anchor-system.md"
    };

    @Test
    void shouldLoadAllKnowledgeFiles() {
        for (String file : EXPECTED_FILES) {
            URL resource = getClass().getClassLoader().getResource("docs/knowledge/" + file);
            assertThat(resource)
                .as("Knowledge file should exist: %s", file)
                .isNotNull();
        }
    }

    // technical-architecture-discovery skill moved to docs/skills/ (integration-time tool, not runtime)
}
