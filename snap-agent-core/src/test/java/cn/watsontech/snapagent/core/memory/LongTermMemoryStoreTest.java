package cn.watsontech.snapagent.core.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for Long-term Memory SPI: UserProfileStore, ProjectFactsStore,
 * and their in-memory default implementations.
 */
@DisplayName("Long-term Memory — UserProfileStore + ProjectFactsStore")
class LongTermMemoryStoreTest {

    // ---- UserProfile ----

    @Test
    @DisplayName("InMemoryUserProfileStore: save + load round-trip")
    void shouldSaveAndLoadUserProfile() {
        InMemoryUserProfileStore store = new InMemoryUserProfileStore();
        UserProfile profile = new UserProfile("user-001", "zh-CN", "concise",
            Arrays.asList("order-service", "payment-service"));
        store.save("user-001", profile);

        UserProfile loaded = store.load("user-001");
        assertThat(loaded).isNotNull();
        assertThat(loaded.getUserId()).isEqualTo("user-001");
        assertThat(loaded.getLanguage()).isEqualTo("zh-CN");
        assertThat(loaded.getOutputStyle()).isEqualTo("concise");
        assertThat(loaded.getFrequentServices()).containsExactly("order-service", "payment-service");
    }

    @Test
    @DisplayName("InMemoryUserProfileStore: load unknown user → null")
    void shouldReturnNullForUnknownUser() {
        InMemoryUserProfileStore store = new InMemoryUserProfileStore();
        assertThat(store.load("unknown")).isNull();
    }

    @Test
    @DisplayName("InMemoryUserProfileStore: save overwrites previous")
    void shouldOverwriteOnSave() {
        InMemoryUserProfileStore store = new InMemoryUserProfileStore();
        store.save("u1", new UserProfile("u1", "en", "detailed", null));
        store.save("u1", new UserProfile("u1", "zh-CN", "concise", null));

        UserProfile loaded = store.load("u1");
        assertThat(loaded.getLanguage()).isEqualTo("zh-CN");
    }

    // ---- ProjectFacts ----

    @Test
    @DisplayName("InMemoryProjectFactsStore: save + load round-trip")
    void shouldSaveAndLoadProjectFacts() {
        InMemoryProjectFactsStore store = new InMemoryProjectFactsStore();
        List<ProjectFact> facts = Arrays.asList(
            new ProjectFact("tech-stack", "Java 8 + Spring Boot 2.x + MySQL"),
            new ProjectFact("coding-convention", "Lombok + MapStruct, no getter/setter")
        );
        store.save("proj-001", facts);

        List<ProjectFact> loaded = store.load("proj-001");
        assertThat(loaded).hasSize(2);
        assertThat(loaded.get(0).getKey()).isEqualTo("tech-stack");
        assertThat(loaded.get(0).getValue()).contains("Java 8");
        assertThat(loaded.get(1).getKey()).isEqualTo("coding-convention");
    }

    @Test
    @DisplayName("InMemoryProjectFactsStore: load unknown project → empty list")
    void shouldReturnEmptyForUnknownProject() {
        InMemoryProjectFactsStore store = new InMemoryProjectFactsStore();
        assertThat(store.load("unknown")).isEmpty();
    }

    @Test
    @DisplayName("InMemoryProjectFactsStore: save null → no-op")
    void shouldHandleNullProjectId() {
        InMemoryProjectFactsStore store = new InMemoryProjectFactsStore();
        store.save(null, Arrays.asList(new ProjectFact("k", "v")));
        assertThat(store.load(null)).isEmpty();
    }
}
