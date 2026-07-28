package cn.watsontech.snapagent.core.memory;

import cn.watsontech.snapagent.core.graph.GraphState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link LongTermMemoryAdvisor} — verifies order (150),
 * beforeNode injection of user profile + project facts into system prompt,
 * and graceful handling when stores are empty or missing.
 */
@DisplayName("LongTermMemoryAdvisor — stable fact injection (order=150)")
class LongTermMemoryAdvisorTest {

    @Test
    @DisplayName("getOrder() = 150")
    void shouldReturnOrder150() {
        LongTermMemoryAdvisor advisor = new LongTermMemoryAdvisor(
            new InMemoryUserProfileStore(), new InMemoryProjectFactsStore(),
            "user.id", "project.id");
        assertThat(advisor.getOrder()).isEqualTo(150);
    }

    @Test
    @DisplayName("getName() = 'long-term-memory'")
    void shouldReturnName() {
        LongTermMemoryAdvisor advisor = new LongTermMemoryAdvisor(
            new InMemoryUserProfileStore(), new InMemoryProjectFactsStore());
        assertThat(advisor.getName()).isEqualTo("long-term-memory");
    }

    @Test
    @DisplayName("null stores → IllegalArgumentException")
    void shouldThrowOnNullStores() {
        assertThatThrownBy(() -> new LongTermMemoryAdvisor(null, new InMemoryProjectFactsStore()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LongTermMemoryAdvisor(new InMemoryUserProfileStore(), null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("beforeNode: injects <user_profile> block when user has profile")
    void shouldInjectUserProfile() throws Exception {
        InMemoryUserProfileStore userStore = new InMemoryUserProfileStore();
        userStore.save("u1", new UserProfile("u1", "zh-CN", "concise",
            Arrays.asList("order-service")));

        LongTermMemoryAdvisor advisor = new LongTermMemoryAdvisor(userStore, new InMemoryProjectFactsStore());

        GraphState state = GraphState.empty("t1")
            .with("system.prompt", "base prompt")
            .with("user.id", "u1");

        GraphState result = advisor.beforeNode("agent", state, null);
        String prompt = result.get("system.prompt");

        assertThat(prompt).contains("<user_profile>");
        assertThat(prompt).contains("zh-CN");
        assertThat(prompt).contains("concise");
        assertThat(prompt).contains("order-service");
        assertThat(prompt).contains("</user_profile>");
    }

    @Test
    @DisplayName("beforeNode: injects <project_facts> block when project has facts")
    void shouldInjectProjectFacts() throws Exception {
        InMemoryProjectFactsStore factsStore = new InMemoryProjectFactsStore();
        factsStore.save("p1", Arrays.asList(
            new ProjectFact("tech-stack", "Java 8 + Spring Boot 2.x"),
            new ProjectFact("convention", "Lombok + MapStruct")
        ));

        LongTermMemoryAdvisor advisor = new LongTermMemoryAdvisor(
            new InMemoryUserProfileStore(), factsStore);

        GraphState state = GraphState.empty("t1")
            .with("system.prompt", "base prompt")
            .with("project.id", "p1");

        GraphState result = advisor.beforeNode("agent", state, null);
        String prompt = result.get("system.prompt");

        assertThat(prompt).contains("<project_facts>");
        assertThat(prompt).contains("tech-stack");
        assertThat(prompt).contains("Java 8 + Spring Boot 2.x");
        assertThat(prompt).contains("convention");
        assertThat(prompt).contains("</project_facts>");
    }

    @Test
    @DisplayName("beforeNode: no user.id or project.id → prompt unchanged")
    void shouldNotModifyPromptWhenNoIds() throws Exception {
        LongTermMemoryAdvisor advisor = new LongTermMemoryAdvisor(
            new InMemoryUserProfileStore(), new InMemoryProjectFactsStore());

        GraphState state = GraphState.empty("t1")
            .with("system.prompt", "base prompt");

        GraphState result = advisor.beforeNode("agent", state, null);
        assertThat(result.<String>get("system.prompt")).isEqualTo("base prompt");
    }

    @Test
    @DisplayName("beforeNode: unknown user → no <user_profile> block")
    void shouldNotInjectForUnknownUser() throws Exception {
        LongTermMemoryAdvisor advisor = new LongTermMemoryAdvisor(
            new InMemoryUserProfileStore(), new InMemoryProjectFactsStore());

        GraphState state = GraphState.empty("t1")
            .with("system.prompt", "base prompt")
            .with("user.id", "unknown-user");

        GraphState result = advisor.beforeNode("agent", state, null);
        assertThat(result.<String>get("system.prompt")).isEqualTo("base prompt");
    }

    @Test
    @DisplayName("beforeNode: only runs before 'agent' node, skips others")
    void shouldOnlyRunBeforeAgentNode() throws Exception {
        InMemoryUserProfileStore userStore = new InMemoryUserProfileStore();
        userStore.save("u1", new UserProfile("u1", "zh-CN", "concise", null));

        LongTermMemoryAdvisor advisor = new LongTermMemoryAdvisor(userStore, new InMemoryProjectFactsStore());

        GraphState state = GraphState.empty("t1")
            .with("system.prompt", "base prompt")
            .with("user.id", "u1");

        GraphState result = advisor.beforeNode("entry", state, null);
        assertThat(result.<String>get("system.prompt")).isEqualTo("base prompt");
    }

    @Test
    @DisplayName("afterNode: returns state unchanged (no-op)")
    void shouldReturnStateUnchangedOnAfterNode() throws Exception {
        LongTermMemoryAdvisor advisor = new LongTermMemoryAdvisor(
            new InMemoryUserProfileStore(), new InMemoryProjectFactsStore());

        GraphState state = GraphState.empty("t1").with("key", "value");
        GraphState result = advisor.afterNode("agent", state, null);
        assertThat(result.<String>get("key")).isEqualTo("value");
    }
}
