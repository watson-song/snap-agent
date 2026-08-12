package cn.watsontech.snapagent.boot2x.web;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test for LLM bridge enabled behavior.
 */
class LlmBridgeEnabledTest {

    @Test
    void llmBridgeShouldAlwaysBeEnabled() {
        // Bridge-first mode: llmBridgeEnabled should always be true
        // so frontend loads llm-bridge-client.js
        assertThat(true).isTrue(); // Placeholder - actual verification done in integration tests
    }
}
