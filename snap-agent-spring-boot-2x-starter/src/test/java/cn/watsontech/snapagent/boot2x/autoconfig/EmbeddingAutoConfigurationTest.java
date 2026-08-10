package cn.watsontech.snapagent.boot2x.autoconfig;

import cn.watsontech.snapagent.core.embedding.EmbeddingModel;
import cn.watsontech.snapagent.core.vectorstore.VectorStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for EmbeddingAutoConfiguration (2.x).
 */
class EmbeddingAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(EmbeddingAutoConfiguration.class));

    @Test
    void shouldNotCreateBeansWhenVectorStoreDisabled() {
        contextRunner
            .withPropertyValues("snap-agent.vectorstore.enabled=false")
            .run(context -> {
                assertThat(context).doesNotHaveBean(EmbeddingModel.class);
                assertThat(context).doesNotHaveBean(VectorStore.class);
            });
    }

    @Test
    void shouldCreateBeansWhenVectorStoreEnabled() {
        contextRunner
            .withPropertyValues(
                "snap-agent.vectorstore.enabled=true",
                "snap-agent.vectorstore.type=in-memory",
                "snap-agent.embedding.provider=openai",
                "snap-agent.embedding.model=text-embedding-3-small",
                "snap-agent.embedding.openai.api-key=test-key"
            )
            .run(context -> {
                assertThat(context).hasSingleBean(EmbeddingModel.class);
                assertThat(context).hasSingleBean(VectorStore.class);
            });
    }

    @Test
    void shouldCreateOllamaEmbeddingModel() {
        contextRunner
            .withPropertyValues(
                "snap-agent.vectorstore.enabled=true",
                "snap-agent.embedding.provider=ollama",
                "snap-agent.embedding.ollama.base-url=http://localhost:11434",
                "snap-agent.embedding.ollama.model=nomic-embed-text"
            )
            .run(context -> {
                assertThat(context).hasSingleBean(EmbeddingModel.class);
            });
    }
}
