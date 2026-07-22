package cn.watsontech.snapagent.core.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolPluginAnnotationTest {

    @ToolPluginAnnotation(id = "my-plugin", toolType = "log_read", displayName = "My Plugin", description = "A test plugin", version = "2.0.0", isDefault = true)
    public static class AnnotatedPlugin {
    }

    @ToolPluginAnnotation(id = "minimal-plugin", toolType = "metrics_query")
    public static class MinimalPlugin {
    }

    @Test
    void shouldReadIdFromAnnotation() {
        ToolPluginAnnotation annotation = AnnotatedPlugin.class.getAnnotation(ToolPluginAnnotation.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.id()).isEqualTo("my-plugin");
    }

    @Test
    void shouldReadToolTypeFromAnnotation() {
        ToolPluginAnnotation annotation = AnnotatedPlugin.class.getAnnotation(ToolPluginAnnotation.class);
        assertThat(annotation.toolType()).isEqualTo("log_read");
    }

    @Test
    void shouldReadDefaultValues() {
        ToolPluginAnnotation annotation = MinimalPlugin.class.getAnnotation(ToolPluginAnnotation.class);
        assertThat(annotation.displayName()).isEmpty();
        assertThat(annotation.description()).isEmpty();
        assertThat(annotation.version()).isEqualTo("1.0.0");
        assertThat(annotation.isDefault()).isFalse();
    }

    @Test
    void shouldReadCustomValues() {
        ToolPluginAnnotation annotation = AnnotatedPlugin.class.getAnnotation(ToolPluginAnnotation.class);
        assertThat(annotation.id()).isEqualTo("my-plugin");
        assertThat(annotation.toolType()).isEqualTo("log_read");
        assertThat(annotation.displayName()).isEqualTo("My Plugin");
        assertThat(annotation.description()).isEqualTo("A test plugin");
        assertThat(annotation.version()).isEqualTo("2.0.0");
        assertThat(annotation.isDefault()).isTrue();
    }
}
