package cn.watsontech.snapagent.boot2x.autoconfig;

import cn.watsontech.snapagent.boot2x.domain.DomainKnowledgeLoader;
import cn.watsontech.snapagent.boot2x.domain.DomainKnowledgeTools;
import cn.watsontech.snapagent.boot2x.domain.InMemoryDomainKnowledgeIndex;
import cn.watsontech.snapagent.core.domain.DomainKnowledgeIndex;
import cn.watsontech.snapagent.core.vectorstore.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Auto-configuration for domain knowledge engine.
 *
 * <p>Loads domain knowledge from Markdown files with YAML frontmatter on
 * application startup. Domain concepts are indexed for reverse lookup
 * (by table/service name) and stored in VectorStore for RAG retrieval.</p>
 *
 * <p>Configuration:</p>
 * <pre>
 * snap-agent:
 *   domain-knowledge:
 *     enabled: true
 *     dir: /path/to/domain-knowledge   # defaults to {upload-skills-dir}/domain-knowledge
 * </pre>
 */
@Configuration
@ConditionalOnProperty(prefix = "snap-agent", name = "enabled", havingValue = "true")
public class DomainKnowledgeAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DomainKnowledgeAutoConfiguration.class);

    @Autowired
    private SnapAgentProperties props;

    @Bean
    @ConditionalOnMissingBean
    public DomainKnowledgeIndex domainKnowledgeIndex() {
        log.info("InMemoryDomainKnowledgeIndex assembled");
        return new InMemoryDomainKnowledgeIndex();
    }

    @Bean
    @ConditionalOnMissingBean
    public DomainKnowledgeLoader domainKnowledgeLoader(
            DomainKnowledgeIndex index,
            ObjectProvider<VectorStore> vectorStoreProvider) {
        log.info("DomainKnowledgeLoader assembled");
        return new DomainKnowledgeLoader(index, vectorStoreProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public DomainKnowledgeTools domainKnowledgeTools(DomainKnowledgeIndex index) {
        log.info("DomainKnowledgeTools assembled (index size: {})", index.size());
        return new DomainKnowledgeTools(index);
    }

    /**
     * Load domain knowledge files on application startup.
     * Scans the configured directory for .md files with YAML frontmatter.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void loadDomainKnowledgeOnStartup() {
        if (!isDomainKnowledgeEnabled()) {
            return;
        }

        String dir = resolveDomainKnowledgeDir();
        Path dirPath = Paths.get(dir);
        if (!Files.isDirectory(dirPath)) {
            log.info("Domain knowledge directory does not exist yet: {}. "
                    + "Create it and add .md files with YAML frontmatter to populate.", dir);
            return;
        }

        DomainKnowledgeLoader loader;
        DomainKnowledgeIndex index;
        try {
            loader = new DomainKnowledgeLoader(
                    (DomainKnowledgeIndex) domainKnowledgeIndex(),
                    null);
        } catch (Exception e) {
            log.warn("Failed to create DomainKnowledgeLoader for startup load: {}", e.getMessage());
            return;
        }

        try {
            int loaded = loader.loadFromDirectory(dirPath).size();
            log.info("Domain knowledge loaded on startup: {} concepts from {}", loaded, dir);
        } catch (Exception e) {
            log.warn("Failed to load domain knowledge from {}: {}", dir, e.getMessage());
        }
    }

    private boolean isDomainKnowledgeEnabled() {
        // Enabled by default when snap-agent is enabled
        String enabled = System.getProperty("snap-agent.domain-knowledge.enabled");
        if ("false".equalsIgnoreCase(enabled)) {
            return false;
        }
        return true;
    }

    private String resolveDomainKnowledgeDir() {
        String configured = props.getUploadSkillsDir() + "/domain-knowledge";
        return configured;
    }
}
