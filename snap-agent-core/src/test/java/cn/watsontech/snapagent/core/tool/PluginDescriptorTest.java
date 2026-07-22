package cn.watsontech.snapagent.core.tool;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

class PluginDescriptorTest {

    private ToolProvider mockProvider() {
        return Mockito.mock(ToolProvider.class);
    }

    @Test
    void shouldHoldAllFields() {
        ToolProvider provider = mockProvider();
        PluginContext ctx = Mockito.mock(PluginContext.class);
        PluginDescriptor desc = new PluginDescriptor(
                "remote-log", "log_read", "Remote Log", "Query Loki", "1.2.0",
                true, true, false, provider, null, Paths.get("/data/p.jar"), ctx);

        assertThat(desc.getPluginId()).isEqualTo("remote-log");
        assertThat(desc.getToolType()).isEqualTo("log_read");
        assertThat(desc.getDisplayName()).isEqualTo("Remote Log");
        assertThat(desc.getDescription()).isEqualTo("Query Loki");
        assertThat(desc.getVersion()).isEqualTo("1.2.0");
        assertThat(desc.isDefault()).isTrue();
        assertThat(desc.isEnabled()).isTrue();
        assertThat(desc.isSystem()).isFalse();
        assertThat(desc.getProvider()).isSameAs(provider);
        assertThat(desc.getClassLoader()).isNull();
        assertThat(desc.getJarPath()).isEqualTo(Paths.get("/data/p.jar"));
        assertThat(desc.getPluginContext()).isSameAs(ctx);
    }

    @Test
    void shouldDefaultEnabledToTrue() {
        PluginDescriptor desc = new PluginDescriptor(
                "p1", "t1", "P1", "", "1.0",
                false, true, true, mockProvider(), null, null, null);

        assertThat(desc.isEnabled()).isTrue();
    }

    @Test
    void shouldAllowModifyingDefaultFlag() {
        PluginDescriptor desc = new PluginDescriptor(
                "p1", "t1", "P1", "", "1.0",
                true, true, true, mockProvider(), null, null, null);

        desc.setDefault(false);
        assertThat(desc.isDefault()).isFalse();

        desc.setDefault(true);
        assertThat(desc.isDefault()).isTrue();
    }

    @Test
    void shouldAllowModifyingEnabledFlag() {
        PluginDescriptor desc = new PluginDescriptor(
                "p1", "t1", "P1", "", "1.0",
                true, true, true, mockProvider(), null, null, null);

        desc.setEnabled(false);
        assertThat(desc.isEnabled()).isFalse();

        desc.setEnabled(true);
        assertThat(desc.isEnabled()).isTrue();
    }

    @Test
    void shouldAllowNullableClassLoaderJarPathAndPluginContext() {
        PluginDescriptor desc = new PluginDescriptor(
                "p1", "t1", "P1", "", "1.0",
                false, true, true, mockProvider(), null, null, null);

        assertThat(desc.getClassLoader()).isNull();
        assertThat(desc.getJarPath()).isNull();
        assertThat(desc.getPluginContext()).isNull();
    }
}
