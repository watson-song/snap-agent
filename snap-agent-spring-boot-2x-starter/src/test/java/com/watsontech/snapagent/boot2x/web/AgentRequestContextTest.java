package com.watsontech.snapagent.boot2x.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AgentRequestContext}.
 */
class AgentRequestContextTest {

    @AfterEach
    void tearDown() {
        AgentRequestContext.clear();
    }

    @Test
    void shouldSetAndGetUserId() {
        AgentRequestContext.setUserId("user001");

        assertThat(AgentRequestContext.getUserId()).isEqualTo("user001");
    }

    @Test
    void shouldReturnNullWhenNoUserIdSet() {
        assertThat(AgentRequestContext.getUserId()).isNull();
    }

    @Test
    void shouldClearContext() {
        AgentRequestContext.setUserId("user001");

        AgentRequestContext.clear();

        assertThat(AgentRequestContext.getUserId()).isNull();
    }
}
