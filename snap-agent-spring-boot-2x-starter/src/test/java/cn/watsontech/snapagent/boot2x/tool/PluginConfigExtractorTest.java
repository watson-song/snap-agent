package cn.watsontech.snapagent.boot2x.tool;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PluginConfigExtractorTest {

    private final PluginConfigExtractor extractor = new PluginConfigExtractor();

    @Test
    void shouldExtractConfigForPlugin() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("snap-agent.tools.remote-log.base-url", "http://loki:3100");
        env.setProperty("snap-agent.tools.remote-log.max-lines", "5000");

        Map<String, Object> config = extractor.extract(env, "remote-log");

        assertThat(config).isNotEmpty();
        assertThat(config).containsEntry("base-url", "http://loki:3100");
        assertThat(config).containsEntry("max-lines", "5000");
    }

    @Test
    void shouldReturnEmptyMapWhenNoConfig() {
        MockEnvironment env = new MockEnvironment();

        Map<String, Object> config = extractor.extract(env, "nonexistent-plugin");

        assertThat(config).isEmpty();
    }

    @Test
    void shouldHandleNullPluginId() {
        MockEnvironment env = new MockEnvironment();

        Map<String, Object> config = extractor.extract(env, null);

        assertThat(config).isEmpty();
    }
}
