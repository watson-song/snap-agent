package cn.watsontech.snapagent.standalone;

import org.junit.jupiter.api.Test;

import java.net.URL;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证知识库文件正确加载到 classpath
 */
class KnowledgeLoadingTest {

    @Test
    void shouldLoadAllKnowledgeFiles() {
        String[] expectedFiles = {
            "snap-agent-architecture.md",
            "snap-agent-memory-system.md",
            "snap-agent-agent-engine.md",
            "snap-agent-advisor-system.md",
            "snap-agent-tool-system.md",
            "snap-agent-graph-framework.md",
            "snap-agent-bridge-system.md",
            "snap-agent-skill-system.md",
            "snap-agent-code-graph.md"
        };

        for (String file : expectedFiles) {
            URL resource = getClass().getClassLoader().getResource("docs/knowledge/" + file);
            assertThat(resource)
                .as("Knowledge file should exist: %s", file)
                .isNotNull();
        }
    }

    @Test
    void shouldHaveTechnicalArchitectureDiscoverySkill() {
        URL resource = getClass().getClassLoader()
            .getResource("docs/skills/technical-architecture-discovery.md");
        assertThat(resource).isNotNull();
    }
}
