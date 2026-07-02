package com.watsontech.snapagent.boot2x.web;

import com.watsontech.snapagent.boot2x.security.DefaultPrincipalResolver;
import com.watsontech.snapagent.core.security.SecurityGateway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.servlet.FilterChain;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SnapAgentFilter}.
 */
class SnapAgentFilterTest {

    private SecurityGateway securityGateway;
    private SnapAgentFilter filter;

    @BeforeEach
    void setUp() {
        securityGateway = mock(SecurityGateway.class);
        filter = new SnapAgentFilter(securityGateway);
    }

    @AfterEach
    void tearDown() {
        AgentRequestContext.clear();
    }

    @Test
    void shouldSetUserIdWhenRequestPathMatches() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getRequestURI()).thenReturn("/snap-agent/skills");
        when(securityGateway.currentUserId()).thenReturn("user001");

        // Capture userId during chain execution (filter clears context in finally)
        final String[] capturedUserId = new String[1];
        doAnswer(invocation -> {
            capturedUserId[0] = AgentRequestContext.getUserId();
            return null;
        }).when(chain).doFilter(request, response);

        filter.doFilter(request, response, chain);

        assertThat(capturedUserId[0]).isEqualTo("user001");
        verify(chain).doFilter(request, response);
    }

    @Test
    void shouldNotProcessWhenPathDoesNotMatch() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getRequestURI()).thenReturn("/api/users");

        filter.doFilter(request, response, chain);

        assertThat(AgentRequestContext.getUserId()).isNull();
        verify(chain).doFilter(request, response);
    }

    @Test
    void shouldClearContextAfterChain() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getRequestURI()).thenReturn("/snap-agent/runs");
        when(securityGateway.currentUserId()).thenReturn("user002");

        filter.doFilter(request, response, chain);

        // After the filter chain completes, context should be cleared
        assertThat(AgentRequestContext.getUserId()).isNull();
    }

    @Test
    void shouldClearContextEvenWhenChainThrows() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getRequestURI()).thenReturn("/snap-agent/runs");
        when(securityGateway.currentUserId()).thenReturn("user003");

        // Simulate exception in chain
        RuntimeException chainError = new RuntimeException("chain error");
        doAnswer(invocation -> {
            // While in the chain, context should be set
            assertThat(AgentRequestContext.getUserId()).isEqualTo("user003");
            throw chainError;
        }).when(chain).doFilter(request, response);

        try {
            filter.doFilter(request, response, chain);
        } catch (RuntimeException expected) {
            // expected
        }

        // Context should be cleared even after exception
        assertThat(AgentRequestContext.getUserId()).isNull();
    }

    @Test
    void shouldSetNullUserIdWhenNotAuthenticated() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getRequestURI()).thenReturn("/snap-agent/skills");
        when(securityGateway.currentUserId()).thenReturn(null);

        filter.doFilter(request, response, chain);

        // Should still proceed (controller will handle 401)
        verify(chain).doFilter(request, response);
        // Context cleared after chain
        assertThat(AgentRequestContext.getUserId()).isNull();
    }
}
