package cn.watsontech.snapagent.boot2x.web;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import cn.watsontech.snapagent.boot2x.tool.PluginUploader;
import cn.watsontech.snapagent.core.security.SecurityGateway;
import cn.watsontech.snapagent.core.tool.InMemoryPluginRegistry;
import cn.watsontech.snapagent.core.tool.PluginDescriptor;
import cn.watsontech.snapagent.core.tool.PluginRegistry;
import cn.watsontech.snapagent.core.tool.ToolProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class PluginEndpointTest {

    private SnapAgentController controller;
    private PluginRegistry registry;
    private SecurityGateway securityGateway;
    private SnapAgentProperties props;

    @BeforeEach
    void setUp() {
        registry = new InMemoryPluginRegistry();
        securityGateway = Mockito.mock(SecurityGateway.class);
        props = new SnapAgentProperties();
        when(securityGateway.currentUserId()).thenReturn("admin");
        when(securityGateway.hasPermission(Mockito.anyString())).thenReturn(true);

        controller = new SnapAgentController(
                null, null, null, null, props, securityGateway,
                null, null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                null, registry, null);
    }

    private void registerPlugin(String id, String toolType, boolean system) {
        ToolProvider provider = Mockito.mock(ToolProvider.class);
        when(provider.name()).thenReturn(id);
        registry.register(new PluginDescriptor(
                id, toolType, id, "desc", "1.0",
                true, true, system, provider, null, null, null));
    }

    @Test
    void shouldListAllPlugins() {
        registerPlugin("mysql", "mysql_query", true);
        registerPlugin("remote-log", "log_read", false);

        ResponseEntity<Object> response = controller.listPlugins();

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> body = (List<Map<String, Object>>) response.getBody();
        assertThat(body).hasSize(2);
        assertThat(body.get(0)).containsKey("pluginId");
        assertThat(body.get(0)).containsKey("toolType");
        assertThat(body.get(0)).containsKey("system");
    }

    @Test
    void shouldReturn404ForUnknownPlugin() {
        registerPlugin("mysql", "mysql_query", true);

        ResponseEntity<Object> response = controller.getPlugin("nonexistent");

        assertThat(response.getStatusCodeValue()).isEqualTo(404);
    }

    @Test
    void shouldReturnPluginDetails() {
        registerPlugin("mysql", "mysql_query", true);

        ResponseEntity<Object> response = controller.getPlugin("mysql");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body.get("pluginId")).isEqualTo("mysql");
        assertThat(body.get("toolType")).isEqualTo("mysql_query");
    }

    @Test
    void shouldReturn403WhenDeletingSystemPlugin() {
        registerPlugin("mysql", "mysql_query", true);

        ResponseEntity<Object> response = controller.deletePlugin("mysql");

        assertThat(response.getStatusCodeValue()).isEqualTo(403);
    }

    @Test
    void shouldDeleteNonSystemPlugin() {
        registerPlugin("custom", "log_read", false);

        ResponseEntity<Object> response = controller.deletePlugin("custom");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(registry.getPlugin("custom")).isNull();
    }

    @Test
    void shouldEnablePlugin() {
        registerPlugin("custom", "log_read", false);
        registry.disable("custom");

        ResponseEntity<Object> response = controller.enablePlugin("custom");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(registry.getPlugin("custom").isEnabled()).isTrue();
    }

    @Test
    void shouldDisablePlugin() {
        registerPlugin("custom", "log_read", false);

        ResponseEntity<Object> response = controller.disablePlugin("custom");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(registry.getPlugin("custom").isEnabled()).isFalse();
    }

    @Test
    void shouldSetDefaultPlugin() {
        registry.register(new PluginDescriptor(
                "log1", "log_read", "L1", "", "1.0",
                true, true, false, Mockito.mock(ToolProvider.class), null, null, null));
        registry.register(new PluginDescriptor(
                "log2", "log_read", "L2", "", "1.0",
                false, true, false, Mockito.mock(ToolProvider.class), null, null, null));

        ResponseEntity<Object> response = controller.setDefaultPlugin("log2");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(registry.getDefault("log_read").getPluginId()).isEqualTo("log2");
        assertThat(registry.getPlugin("log1").isDefault()).isFalse();
    }

    @Test
    void shouldReturn404WhenEnablingUnknownPlugin() {
        ResponseEntity<Object> response = controller.enablePlugin("nonexistent");

        assertThat(response.getStatusCodeValue()).isEqualTo(404);
    }

    // --- G-03E: POST /tools/plugins/upload ---

    @Test
    void shouldUploadPluginJarSuccessfully() {
        PluginUploader uploader = Mockito.mock(PluginUploader.class);
        ToolProvider provider = Mockito.mock(ToolProvider.class);
        when(provider.name()).thenReturn("custom_tool");
        PluginDescriptor desc = new PluginDescriptor(
                "custom-plugin", "log_read", "Custom", "desc", "1.0.0",
                false, true, false, provider, null, null, null);
        when(uploader.upload(Mockito.any())).thenReturn(desc);

        SnapAgentController uploadController = new SnapAgentController(
                null, null, null, null, props, securityGateway,
                null, null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                null, registry, uploader);

        MockMultipartFile file = new MockMultipartFile(
                "file", "plugin.jar", "application/java-archive", new byte[]{1, 2, 3});

        ResponseEntity<Object> response = uploadController.uploadPlugin(file);

        assertThat(response.getStatusCodeValue()).isEqualTo(201);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body.get("pluginId")).isEqualTo("custom-plugin");
        assertThat(body.get("toolType")).isEqualTo("log_read");
        assertThat(body.get("system")).isEqualTo(false);
    }

    @Test
    void shouldReturn400WhenUploadFileIsEmpty() {
        PluginUploader uploader = Mockito.mock(PluginUploader.class);

        SnapAgentController uploadController = new SnapAgentController(
                null, null, null, null, props, securityGateway,
                null, null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                null, registry, uploader);

        MockMultipartFile emptyFile = new MockMultipartFile(
                "file", "empty.jar", "application/java-archive", new byte[0]);

        ResponseEntity<Object> response = uploadController.uploadPlugin(emptyFile);

        assertThat(response.getStatusCodeValue()).isEqualTo(400);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body.get("error")).isEqualTo("INVALID_INPUT");
        Mockito.verify(uploader, Mockito.never()).upload(Mockito.any());
    }
}
