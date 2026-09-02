package cn.watsontech.snapagent.boot2x.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.StreamUtils;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for static resource serving (GET /snap-agent/index.html, anchor.js).
 *
 * <p>Module 11, AC18: Given SnapAgentAutoConfiguration is deployed with static
 * resources at {@code classpath:/static/snap-agent/}, when a client performs
 * GET /snap-agent/index.html, then the response should be HTML.</p>
 *
 * <p>SnapAgentAutoConfiguration does not register a custom {@link WebMvcConfigurer};
 * it relies on Spring Boot's default {@code WebMvcAutoConfiguration} which serves
 * files from {@code classpath:/static/} at {@code /**}. Since
 * {@code /snap-agent/**} is a sub-path of {@code /**}, requests like
 * {@code GET /snap-agent/index.html} resolve to
 * {@code classpath:/static/snap-agent/index.html}.</p>
 *
 * <p>To exercise the Spring MVC resource handler pipeline end-to-end without
 * {@code @SpringBootTest}, this test builds a minimal
 * {@link AnnotationConfigWebApplicationContext} with {@link EnableWebMvc}
 * and a {@link WebMvcConfigurer} that registers the same resource handler
 * pattern and location Spring Boot's auto-configuration would use. This
 * produces a real {@link MockMvc} backed by the full resource handler stack
 * (ResourceHttpRequestHandler + HttpRequestHandlerAdapter), so the assertion
 * "GET /snap-agent/index.html returns HTML" is verified through the actual
 * Spring MVC handler chain rather than a standalone MockMvc stub.</p>
 */
@ExtendWith(MockitoExtension.class)
class StaticResourceTest {

    private MockMvc mockMvc;
    private AnnotationConfigWebApplicationContext wac;

    /**
     * Minimal Spring MVC configuration mirroring Spring Boot's default
     * static resource serving for the snap-agent path.
     *
     * <p>{@link EnableWebMvc @EnableWebMvc} triggers
     * {@code WebMvcConfigurationSupport}, which registers the
     * {@code HttpRequestHandlerAdapter} needed to adapt
     * {@code ResourceHttpRequestHandler} (the handler that ultimately
     * streams the static file content to the response).</p>
     */
    @Configuration
    @EnableWebMvc
    static class StaticResourceConfig implements WebMvcConfigurer {
        @Override
        public void addResourceHandlers(ResourceHandlerRegistry registry) {
            registry.addResourceHandler("/snap-agent/**")
                    .addResourceLocations("classpath:/static/snap-agent/");
        }
    }

    @BeforeEach
    void setUp() {
        wac = new AnnotationConfigWebApplicationContext();
        wac.setServletContext(new MockServletContext());
        wac.register(StaticResourceConfig.class);
        wac.refresh();
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
    }

    @AfterEach
    void tearDown() {
        if (wac != null) {
            wac.close();
        }
    }

    // ---- AC18: GET /snap-agent/index.html returns HTML ----

    @Test
    void shouldReturnHtmlWhenGetSnapAgentIndexHtml() throws Exception {
        mockMvc.perform(get("/snap-agent/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("<title>SnapAgent</title>")));
    }

    @Test
    void shouldReturnJavaScriptWhenGetAnchorJs() throws Exception {
        mockMvc.perform(get("/snap-agent/anchor.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("SnapAgent Anchor")));
    }

    @Test
    void shouldReturn404WhenGetNonExistentStaticResource() throws Exception {
        mockMvc.perform(get("/snap-agent/nonexistent-file.html"))
                .andExpect(status().isNotFound());
    }

    // ---- Supporting assertions: files exist at expected classpath location ----

    @Test
    void shouldExistInClasspathWhenCheckExpectedStaticFiles() {
        // SnapAgentAutoConfiguration relies on Spring Boot's default
        // WebMvcAutoConfiguration serving files from classpath:/static/
        // at /**. Verify all expected files exist at the sub-path
        // /static/snap-agent/ so that GET /snap-agent/<file> resolves.
        assertThat(new ClassPathResource("static/snap-agent/index.html").exists())
                .as("index.html should exist in classpath").isTrue();
        assertThat(new ClassPathResource("static/snap-agent/anchor.js").exists())
                .as("anchor.js should exist in classpath").isTrue();
        assertThat(new ClassPathResource("static/snap-agent/app.js").exists())
                .as("app.js should exist in classpath").isTrue();
        assertThat(new ClassPathResource("static/snap-agent/style.css").exists())
                .as("style.css should exist in classpath").isTrue();
    }

    @Test
    void shouldContainHtmlDoctypeWhenReadIndexHtmlContentFromClasspath() throws Exception {
        // Belt-and-braces: verify the index.html content has the expected
        // HTML markers independent of the MockMvc pipeline.
        ClassPathResource resource = new ClassPathResource("static/snap-agent/index.html");
        try (InputStream in = resource.getInputStream()) {
            String html = StreamUtils.copyToString(in, StandardCharsets.UTF_8);
            assertThat(html)
                    .contains("<!DOCTYPE html>")
                    .contains("<title>SnapAgent</title>");
        }
    }

    // ---- SPA Enhancement: skill search filter + new conversation button ----

    @Test
    void indexHtmlShouldContainSkillSearchInput() throws Exception {
        ClassPathResource resource = new ClassPathResource("static/snap-agent/index.html");
        try (InputStream in = resource.getInputStream()) {
            String html = StreamUtils.copyToString(in, StandardCharsets.UTF_8);
            assertThat(html)
                    .as("Skill search input should be present in sidebar")
                    .contains("skillSearchInput")
                    .contains("skill-search-input");
        }
    }

    @Test
    void indexHtmlShouldContainNewConversationButton() throws Exception {
        ClassPathResource resource = new ClassPathResource("static/snap-agent/index.html");
        try (InputStream in = resource.getInputStream()) {
            String html = StreamUtils.copyToString(in, StandardCharsets.UTF_8);
            assertThat(html)
                    .as("New conversation button should be present in input bar")
                    .contains("newConvBtn")
                    .contains("btn-new-conv");
        }
    }

    @Test
    void appJsShouldContainSkillSearchFilterLogic() throws Exception {
        ClassPathResource resource = new ClassPathResource("static/snap-agent/app.js");
        try (InputStream in = resource.getInputStream()) {
            String js = StreamUtils.copyToString(in, StandardCharsets.UTF_8);
            assertThat(js)
                    .as("app.js should contain skill search filter implementation")
                    .contains("skillSearchInput")
                    .contains("Skill Search Filter")
                    .contains("ctrlKey");
        }
    }

    @Test
    void appJsShouldContainNewConversationHandler() throws Exception {
        ClassPathResource resource = new ClassPathResource("static/snap-agent/app.js");
        try (InputStream in = resource.getInputStream()) {
            String js = StreamUtils.copyToString(in, StandardCharsets.UTF_8);
            assertThat(js)
                    .as("app.js should contain new conversation button handler")
                    .contains("newConvBtn")
                    .contains("New Conversation Button");
        }
    }

    @Test
    void appJsShouldContainHistoryFilterDropdown() throws Exception {
        ClassPathResource resource = new ClassPathResource("static/snap-agent/app.js");
        try (InputStream in = resource.getInputStream()) {
            String js = StreamUtils.copyToString(in, StandardCharsets.UTF_8);
            assertThat(js)
                    .as("app.js should contain history modal skill filter and search")
                    .contains("historySkillFilter")
                    .contains("historySearchInput")
                    .contains("history-modal-filters");
        }
    }

    @Test
    void styleCssShouldContainSpaEnhancementStyles() throws Exception {
        ClassPathResource resource = new ClassPathResource("static/snap-agent/style.css");
        try (InputStream in = resource.getInputStream()) {
            String css = StreamUtils.copyToString(in, StandardCharsets.UTF_8);
            assertThat(css)
                    .as("style.css should contain styles for new SPA components")
                    .contains(".skill-search-input")
                    .contains(".btn-new-conv")
                    .contains(".history-modal-filters")
                    .contains(".history-skill-filter");
        }
    }
}
