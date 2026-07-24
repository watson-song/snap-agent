package cn.watsontech.snapagent.boot2x.knowledge;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import cn.watsontech.snapagent.boot2x.web.KnowledgeController;
import cn.watsontech.snapagent.core.knowledge.KnowledgeBase;
import cn.watsontech.snapagent.core.knowledge.KnowledgeFragment;
import cn.watsontech.snapagent.core.knowledge.SearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for knowledge base REST endpoints in {@link KnowledgeController}.
 *
 * <p>Covers GET /knowledge/status, GET /knowledge/search,
 * GET /knowledge/fragments, POST /knowledge/reload, POST /knowledge/upload.
 * TDD spec: 06-knowledge UC-R1..R5.</p>
 */
class KnowledgeControllerTest {

    private MockMvc mockMvc;
    private KnowledgeBase knowledgeBase;

    @BeforeEach
    void setUp() {
        knowledgeBase = mock(KnowledgeBase.class);

        SnapAgentProperties properties = new SnapAgentProperties();
        properties.getKnowledge().setEnabled(true);
        properties.getKnowledge().setMaxFragments(3);
        properties.getKnowledge().setMinScore(0.1);

        SnapAgentProperties.KnowledgeSourceConfig src = new SnapAgentProperties.KnowledgeSourceConfig();
        src.setType("markdown");
        src.setDir("/tmp/knowledge-test");
        properties.getKnowledge().setSources(Collections.singletonList(src));

        KnowledgeController controller = new KnowledgeController(knowledgeBase, properties);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private KnowledgeFragment sampleFragment(String title, String content, String source) {
        Map<String, String> metadata = new LinkedHashMap<String, String>();
        metadata.put("category", "business");
        return new KnowledgeFragment(title, content, source, metadata);
    }

    // ── GET /knowledge/status ────────────────────────────────────

    @Test
    @DisplayName("GET /knowledge/status returns enabled status with fragment count")
    void shouldReturnKnowledgeStatus() throws Exception {
        when(knowledgeBase.size()).thenReturn(42);

        mockMvc.perform(get("/snap-agent/knowledge/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.fragmentCount").value(42))
                .andExpect(jsonPath("$.maxFragments").value(3))
                .andExpect(jsonPath("$.minScore").value(0.1))
                .andExpect(jsonPath("$.sources[0].type").value("markdown"))
                .andExpect(jsonPath("$.sources[0].dir").value("/tmp/knowledge-test"))
                .andExpect(jsonPath("$.sources.length()").value(1));
    }

    // ── GET /knowledge/search ────────────────────────────────────

    @Test
    @DisplayName("GET /knowledge/search?q=returns matching fragments with scores")
    void shouldSearchKnowledge() throws Exception {
        KnowledgeFragment frag = sampleFragment("补货策略", "补货策略生成规则...", "business.md:section-3");
        when(knowledgeBase.searchWithScores(eq("补货"), anyInt(), anyDouble()))
                .thenReturn(Collections.singletonList(new SearchResult(frag, 0.85)));
        when(knowledgeBase.size()).thenReturn(10);

        mockMvc.perform(get("/snap-agent/knowledge/search").param("q", "补货"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query").value("补货"))
                .andExpect(jsonPath("$.totalFragments").value(10))
                .andExpect(jsonPath("$.matched").value(1))
                .andExpect(jsonPath("$.fragments[0].title").value("补货策略"))
                .andExpect(jsonPath("$.fragments[0].content").value("补货策略生成规则..."))
                .andExpect(jsonPath("$.fragments[0].source").value("business.md:section-3"))
                .andExpect(jsonPath("$.fragments[0].score").value(0.85));
    }

    @Test
    @DisplayName("GET /knowledge/search returns empty results when no match")
    void shouldReturnEmptySearchResults() throws Exception {
        when(knowledgeBase.searchWithScores(eq("nonexistent"), anyInt(), anyDouble()))
                .thenReturn(Collections.<SearchResult>emptyList());
        when(knowledgeBase.size()).thenReturn(5);

        mockMvc.perform(get("/snap-agent/knowledge/search").param("q", "nonexistent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matched").value(0))
                .andExpect(jsonPath("$.fragments.length()").value(0));
    }

    @Test
    @DisplayName("GET /knowledge/search?q= (empty query) returns empty results")
    void shouldReturnEmptyResultsWhenQueryIsEmpty() throws Exception {
        when(knowledgeBase.searchWithScores(eq(""), anyInt(), anyDouble()))
                .thenReturn(Collections.<SearchResult>emptyList());
        when(knowledgeBase.size()).thenReturn(5);

        mockMvc.perform(get("/snap-agent/knowledge/search").param("q", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query").value(""))
                .andExpect(jsonPath("$.matched").value(0))
                .andExpect(jsonPath("$.fragments.length()").value(0));
    }

    @Test
    @DisplayName("GET /knowledge/search without q param returns 400")
    void shouldReturn400WhenQueryParamMissing() throws Exception {
        mockMvc.perform(get("/snap-agent/knowledge/search"))
                .andExpect(status().isBadRequest());
    }

    // ── GET /knowledge/fragments ─────────────────────────────────

    @Test
    @DisplayName("GET /knowledge/fragments returns all cached fragments")
    void shouldListAllFragments() throws Exception {
        when(knowledgeBase.listAll()).thenReturn(Arrays.asList(
                sampleFragment("Fragment A", "content A", "source-a.md"),
                sampleFragment("Fragment B", "content B", "source-b.md")));

        mockMvc.perform(get("/snap-agent/knowledge/fragments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.fragments.length()").value(2))
                .andExpect(jsonPath("$.fragments[0].title").value("Fragment A"))
                .andExpect(jsonPath("$.fragments[1].title").value("Fragment B"));
    }

    @Test
    @DisplayName("GET /knowledge/fragments returns empty when no fragments")
    void shouldReturnEmptyFragments() throws Exception {
        when(knowledgeBase.listAll()).thenReturn(Collections.<KnowledgeFragment>emptyList());

        mockMvc.perform(get("/snap-agent/knowledge/fragments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.fragments.length()").value(0));
    }

    // ── POST /knowledge/reload ──────────────────────────────────

    @Test
    @DisplayName("POST /knowledge/reload reloads knowledge base and returns new count")
    void shouldReloadKnowledge() throws Exception {
        when(knowledgeBase.size()).thenReturn(15);

        mockMvc.perform(post("/snap-agent/knowledge/reload"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reloaded").value(true))
                .andExpect(jsonPath("$.fragmentCount").value(15));

        verify(knowledgeBase).reload();
    }

    @Test
    @DisplayName("POST /knowledge/reload returns 500 when reload fails")
    void shouldReturn500WhenReloadFails() throws Exception {
        doThrow(new RuntimeException("disk error")).when(knowledgeBase).reload();

        mockMvc.perform(post("/snap-agent/knowledge/reload"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("reload failed")));
    }

    // ── POST /knowledge/upload ───────────────────────────────────

    @Test
    @DisplayName("POST /knowledge/upload accepts .md file and reloads")
    void shouldUploadMarkdownFile() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "test.md",
                "text/markdown", "# Test\n\ncontent".getBytes());

        when(knowledgeBase.size()).thenReturn(20);

        mockMvc.perform(multipart("/snap-agent/knowledge/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filename").value("test.md"))
                .andExpect(jsonPath("$.fragmentCount").value(20));

        verify(knowledgeBase).reload();
    }

    @Test
    @DisplayName("POST /knowledge/upload returns 400 when file is empty")
    void shouldReturn400WhenFileEmpty() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "empty.md",
                "text/markdown", new byte[0]);

        mockMvc.perform(multipart("/snap-agent/knowledge/upload").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("file is empty"));
    }

    @Test
    @DisplayName("POST /knowledge/upload returns 400 when file is not .md")
    void shouldReturn400WhenNotMarkdown() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "test.txt",
                "text/plain", "content".getBytes());

        mockMvc.perform(multipart("/snap-agent/knowledge/upload").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("only .md files are supported"));
    }

    @Test
    @DisplayName("POST /knowledge/upload returns 400 when no file part is present")
    void shouldReturn400WhenNoFilePart() throws Exception {
        mockMvc.perform(multipart("/snap-agent/knowledge/upload"))
                .andExpect(status().isBadRequest());
    }

    // ── Authentication/permission gaps (E2E-6) ────────────────────
    // KnowledgeController currently has no SecurityGateway dependency,
    // so these endpoints are accessible without authentication.
    // These tests document the expected behaviour once auth is wired in.

    @Test
    @Disabled("KnowledgeController does not yet enforce authentication (E2E-6 gap); "
            + "enable when SecurityGateway is wired into KnowledgeController")
    @DisplayName("GET /knowledge/status without auth returns 401")
    void shouldReturn401WhenStatusAccessedWithoutAuth() throws Exception {
        mockMvc.perform(get("/snap-agent/knowledge/status"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Disabled("KnowledgeController does not yet enforce permission checks (E2E-6 gap); "
            + "enable when SecurityGateway is wired into KnowledgeController")
    @DisplayName("POST /knowledge/upload without permission returns 403")
    void shouldReturn403WhenUploadWithoutPermission() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "test.md",
                "text/markdown", "# Test".getBytes());

        mockMvc.perform(multipart("/snap-agent/knowledge/upload").file(file))
                .andExpect(status().isForbidden());
    }
}
