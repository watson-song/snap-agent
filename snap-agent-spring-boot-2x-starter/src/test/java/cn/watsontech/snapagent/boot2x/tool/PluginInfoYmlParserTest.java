package cn.watsontech.snapagent.boot2x.tool;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class PluginInfoYmlParserTest {

    private final PluginInfoYmlParser parser = new PluginInfoYmlParser();

    @Test
    void shouldParseAllFieldsFromYaml() {
        InputStream yamlStream = getClass().getResourceAsStream("/plugin-info-test.yml");
        PluginInfoYml info = parser.parse(yamlStream);

        assertThat(info).isNotNull();
        assertThat(info.getId()).isEqualTo("remote-log");
        assertThat(info.getToolType()).isEqualTo("log_read");
        assertThat(info.getDisplayName()).isEqualTo("远程日志查询");
        assertThat(info.getDescription()).isEqualTo("查询 Loki 历史日志");
        assertThat(info.getVersion()).isEqualTo("1.2.0");
        assertThat(info.isDefault()).isFalse();
        assertThat(info.getProviderClass()).isEqualTo("com.example.RemoteLogToolProvider");
    }

    @Test
    void shouldHandleMissingOptionalFields() {
        String yaml = "id: minimal-plugin\ntoolType: metrics_query\nproviderClass: com.example.MinimalProvider\n";
        InputStream yamlStream = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
        PluginInfoYml info = parser.parse(yamlStream);

        assertThat(info).isNotNull();
        assertThat(info.getId()).isEqualTo("minimal-plugin");
        assertThat(info.getToolType()).isEqualTo("metrics_query");
        assertThat(info.getProviderClass()).isEqualTo("com.example.MinimalProvider");
        assertThat(info.getDisplayName()).isNull();
        assertThat(info.getDescription()).isNull();
        assertThat(info.getVersion()).isNull();
        assertThat(info.isDefault()).isFalse();
    }

    @Test
    void shouldReturnNullWhenYamlIsEmpty() {
        InputStream yamlStream = new ByteArrayInputStream("".getBytes(StandardCharsets.UTF_8));
        PluginInfoYml info = parser.parse(yamlStream);

        assertThat(info).isNull();
    }
}
