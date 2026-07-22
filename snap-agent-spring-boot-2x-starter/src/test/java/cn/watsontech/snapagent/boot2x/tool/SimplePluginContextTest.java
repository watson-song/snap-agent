package cn.watsontech.snapagent.boot2x.tool;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimplePluginContextTest {

    @Test
    void shouldReturnConfigurationAsUnmodifiable() {
        Map<String, Object> config = new HashMap<>();
        config.put("url", "http://example.com");
        SimplePluginContext context = new SimplePluginContext(config);

        Map<String, Object> returned = context.getConfiguration();
        assertThat(returned).containsEntry("url", "http://example.com");

        assertThatThrownBy(() -> returned.put("new-key", "new-value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldHandleNullConfigurationAsEmptyMap() {
        SimplePluginContext context = new SimplePluginContext(null);

        Map<String, Object> returned = context.getConfiguration();
        assertThat(returned).isEmpty();
    }

    @Test
    void shouldStoreCopyOfConfiguration() {
        Map<String, Object> config = new HashMap<>();
        config.put("url", "http://original.com");
        SimplePluginContext context = new SimplePluginContext(config);

        // Mutate the original map
        config.put("url", "http://tampered.com");
        config.put("injected", "bad");

        // Context should be unaffected
        assertThat(context.getConfiguration())
                .containsEntry("url", "http://original.com")
                .doesNotContainKey("injected");
    }
}
