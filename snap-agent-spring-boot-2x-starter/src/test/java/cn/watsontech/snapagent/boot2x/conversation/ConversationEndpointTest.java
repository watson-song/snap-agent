package cn.watsontech.snapagent.boot2x.conversation;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import cn.watsontech.snapagent.boot2x.web.SnapAgentController;
import cn.watsontech.snapagent.core.agent.RateLimiter;
import cn.watsontech.snapagent.core.agent.TaskStore;
import cn.watsontech.snapagent.core.conversation.Conversation;
import cn.watsontech.snapagent.core.conversation.ConversationMessage;
import cn.watsontech.snapagent.core.conversation.ConversationSummary;
import cn.watsontech.snapagent.core.conversation.ConversationStore;
import cn.watsontech.snapagent.core.security.SecurityGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Arrays;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for conversation REST endpoints in {@link SnapAgentController}.
 *
 * <p>Covers POST /conversations, GET /conversations, GET /conversations/{id},
 * GET /conversations/{id}/download, DELETE /conversations/{id}.
 * TDD spec: 11-host-integration UC-R1..R5.</p>
 */
class ConversationEndpointTest {

    private MockMvc mockMvc;
    private ConversationStore conversationStore;

    @BeforeEach
    void setUp() {
        conversationStore = mock(ConversationStore.class);

        SecurityGateway securityGateway = mock(SecurityGateway.class);
        when(securityGateway.currentUserId()).thenReturn("test-user");
        when(securityGateway.hasPermission(anyString())).thenReturn(true);

        SnapAgentProperties props = new SnapAgentProperties();
        props.setEnabled(true);

        SnapAgentController controller = new SnapAgentController(
                null, null, mock(TaskStore.class), null, props, securityGateway,
                mock(RateLimiter.class), null, null, null, null, conversationStore,
                null, null, null, null, null, null, null, null, null, null, null);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private Conversation sampleConversation(String id, String userId, String skillId) {
        return new Conversation(id, userId, skillId, "Test Chat",
                1000L, 2000L,
                Arrays.asList(
                        new ConversationMessage("user", "hello", 1000L, null),
                        new ConversationMessage("assistant", "hi there", 1100L, "task-1")));
    }

    // ── POST /conversations ───────────────────────────────────────

    @Test
    @DisplayName("POST /conversations saves and returns conversation metadata")
    void shouldSaveConversation() throws Exception {
        Conversation saved = sampleConversation("conv-1", "test-user", "health-patrol");
        when(conversationStore.save(any(Conversation.class))).thenReturn(saved);

        mockMvc.perform(post("/snap-agent/conversations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skillId\":\"health-patrol\",\"title\":\"Test Chat\","
                                + "\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").value("conv-1"))
                .andExpect(jsonPath("$.skillId").value("health-patrol"))
                .andExpect(jsonPath("$.title").value("Test Chat"))
                .andExpect(jsonPath("$.messageCount").value(2));
    }

    @Test
    @DisplayName("POST /conversations returns 400 when skillId is missing")
    void shouldReturn400WhenSkillIdMissing() throws Exception {
        mockMvc.perform(post("/snap-agent/conversations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Test\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_INPUT"));
    }

    // ── GET /conversations ───────────────────────────────────────

    @Test
    @DisplayName("GET /conversations lists conversations for current user")
    void shouldListConversations() throws Exception {
        when(conversationStore.list("test-user", null)).thenReturn(Arrays.asList(
                new ConversationSummary("conv-1", "test-user", "health-patrol",
                        "Chat 1", 1000L, 2000L, 2),
                new ConversationSummary("conv-2", "test-user", "code-review",
                        "Chat 2", 3000L, 4000L, 5)));

        mockMvc.perform(get("/snap-agent/conversations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversations.length()").value(2))
                .andExpect(jsonPath("$.conversations[0].conversationId").value("conv-1"))
                .andExpect(jsonPath("$.conversations[1].conversationId").value("conv-2"));
    }

    @Test
    @DisplayName("GET /conversations?skillId=health-patrol filters by skill")
    void shouldFilterConversationsBySkill() throws Exception {
        when(conversationStore.list("test-user", "health-patrol")).thenReturn(
                Collections.singletonList(
                        new ConversationSummary("conv-1", "test-user", "health-patrol",
                                "Chat 1", 1000L, 2000L, 2)));

        mockMvc.perform(get("/snap-agent/conversations?skillId=health-patrol"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversations.length()").value(1))
                .andExpect(jsonPath("$.conversations[0].skillId").value("health-patrol"));
    }

    // ── GET /conversations/{id} ──────────────────────────────────

    @Test
    @DisplayName("GET /conversations/{id} returns conversation with messages")
    void shouldLoadConversation() throws Exception {
        Conversation conv = sampleConversation("conv-1", "test-user", "health-patrol");
        when(conversationStore.load("conv-1", "test-user")).thenReturn(conv);

        mockMvc.perform(get("/snap-agent/conversations/conv-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").value("conv-1"))
                .andExpect(jsonPath("$.skillId").value("health-patrol"))
                .andExpect(jsonPath("$.title").value("Test Chat"))
                .andExpect(jsonPath("$.messages.length()").value(2))
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andExpect(jsonPath("$.messages[1].role").value("assistant"))
                .andExpect(jsonPath("$.messages[1].taskId").value("task-1"));
    }

    @Test
    @DisplayName("GET /conversations/{id} returns 404 when not found")
    void shouldReturn404WhenConversationNotFound() throws Exception {
        when(conversationStore.load("nonexistent", "test-user")).thenReturn(null);

        mockMvc.perform(get("/snap-agent/conversations/nonexistent"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("CONVERSATION_NOT_FOUND"));
    }

    // ── GET /conversations/{id}/download ────────────────────────

    @Test
    @DisplayName("GET /conversations/{id}/download returns markdown")
    void shouldDownloadConversationAsMarkdown() throws Exception {
        Conversation conv = sampleConversation("conv-1", "test-user", "health-patrol");
        when(conversationStore.exportMarkdown("conv-1", "test-user")).thenReturn("# Test Chat\n\nhello\n");
        when(conversationStore.load("conv-1", "test-user")).thenReturn(conv);

        mockMvc.perform(get("/snap-agent/conversations/conv-1/download"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("text/markdown")))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")));
    }

    @Test
    @DisplayName("GET /conversations/{id}/download returns 404 when not found")
    void shouldReturn404WhenDownloadNotFound() throws Exception {
        when(conversationStore.exportMarkdown("nonexistent", "test-user")).thenReturn(null);

        mockMvc.perform(get("/snap-agent/conversations/nonexistent/download"))
                .andExpect(status().isNotFound());
    }

    // ── DELETE /conversations/{id} ───────────────────────────────

    @Test
    @DisplayName("DELETE /conversations/{id} deletes and returns confirmation")
    void shouldDeleteConversation() throws Exception {
        when(conversationStore.delete("conv-1", "test-user")).thenReturn(true);

        mockMvc.perform(delete("/snap-agent/conversations/conv-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value("conv-1"));
    }

    @Test
    @DisplayName("DELETE /conversations/{id} returns 404 when not found")
    void shouldReturn404WhenDeleteNotFound() throws Exception {
        when(conversationStore.delete("nonexistent", "test-user")).thenReturn(false);

        mockMvc.perform(delete("/snap-agent/conversations/nonexistent"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("CONVERSATION_NOT_FOUND"));
    }

    // ── 503 when conversation store disabled ─────────────────────

    @Test
    @DisplayName("GET /conversations returns 503 when conversation disabled")
    void shouldReturn503WhenConversationDisabled() throws Exception {
        SecurityGateway sg = mock(SecurityGateway.class);
        when(sg.currentUserId()).thenReturn("test-user");
        when(sg.hasPermission(anyString())).thenReturn(true);
        SnapAgentController disabledController = new SnapAgentController(
                null, null, mock(TaskStore.class), null, new SnapAgentProperties(), sg,
                mock(RateLimiter.class), null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null);
        MockMvc disabledMvc = MockMvcBuilders.standaloneSetup(disabledController).build();

        disabledMvc.perform(get("/snap-agent/conversations"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("CONVERSATION_DISABLED"));
    }
}
