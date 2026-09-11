package cn.watsontech.snapagent.boot2x.web;

import cn.watsontech.snapagent.boot2x.skill.SkillTemplateCatalog;
import cn.watsontech.snapagent.core.skill.SkillRegistry;
import cn.watsontech.snapagent.core.skill.SkillTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@DisplayName("SkillTemplateController")
class SkillTemplateControllerTest {

    @TempDir
    Path tempDir;

    private SkillTemplateCatalog catalog;
    private SkillTemplateController controller;

    @BeforeEach
    void setUp() {
        SkillRegistry skillRegistry = Mockito.mock(SkillRegistry.class);
        when(skillRegistry.all()).thenReturn(Collections.emptyList());
        catalog = new SkillTemplateCatalog(tempDir, skillRegistry);
        controller = new SkillTemplateController(catalog);
    }

    @Test
    @DisplayName("listTemplates returns 200 with templates array")
    void shouldListTemplates() {
        ResponseEntity<Object> response = controller.listTemplates(null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsKey("templates");
        assertThat(body).containsKey("total");
        assertThat(body).containsKey("categories");
    }

    @Test
    @DisplayName("getTemplate returns 404 for unknown template")
    void shouldReturn404ForUnknownTemplate() {
        ResponseEntity<Object> response = controller.getTemplate("nonexistent");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("installTemplate returns 404 for unknown template")
    void shouldReturn404ForInstallUnknown() {
        ResponseEntity<Object> response = controller.installTemplate("nonexistent");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("uninstallTemplate returns appropriate response")
    void shouldReturnResponseForUninstall() {
        ResponseEntity<Object> response = controller.uninstallTemplate("nonexistent");
        // Should return NOT_FOUND since nothing is installed
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("constructor rejects null catalog")
    void shouldRejectNullCatalog() {
        assertThatThrownBy(() -> new SkillTemplateController(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SkillTemplateCatalog");
    }
}
