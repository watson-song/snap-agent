package com.watsontech.snapagent.boot2x.security;

import com.watsontech.snapagent.core.security.PrincipalResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SpringSecurityAdapter}.
 *
 * <p>Covers currentUserId (String principal), hasPermission (empty=true,
 * exact match), and null authentication (TDD_SPEC §UC-18).</p>
 */
class SpringSecurityAdapterTest {

    private PrincipalResolver principalResolver;
    private SpringSecurityAdapter adapter;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        principalResolver = new DefaultPrincipalResolver();
        adapter = new SpringSecurityAdapter(principalResolver);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldReturnUserIdWhenPrincipalIsString() {
        setAuthentication("user001", Collections.<GrantedAuthority>emptyList());

        assertThat(adapter.currentUserId()).isEqualTo("user001");
    }

    @Test
    void shouldReturnNullWhenNoAuthentication() {
        assertThat(adapter.currentUserId()).isNull();
    }

    @Test
    void shouldReturnTrueWhenPermissionCodeIsEmpty() {
        assertThat(adapter.hasPermission("")).isTrue();
    }

    @Test
    void shouldReturnTrueWhenPermissionCodeIsNull() {
        assertThat(adapter.hasPermission(null)).isTrue();
    }

    @Test
    void shouldReturnTrueWhenUserHasExactAuthority() {
        setAuthentication("user", Arrays.<GrantedAuthority>asList(
                new SimpleGrantedAuthority("skills:run"),
                new SimpleGrantedAuthority("other")));

        assertThat(adapter.hasPermission("skills:run")).isTrue();
    }

    @Test
    void shouldReturnFalseWhenUserLacksAuthority() {
        setAuthentication("user", Arrays.<GrantedAuthority>asList(
                new SimpleGrantedAuthority("skills:run")));

        assertThat(adapter.hasPermission("skills:admin")).isFalse();
    }

    @Test
    void shouldReturnFalseWhenNoAuthenticationAndPermissionRequired() {
        assertThat(adapter.hasPermission("skills:run")).isFalse();
    }

    @Test
    void shouldUsePrincipalResolverForNonStringPrincipal() {
        Object customPrincipal = new Object() {
            public String getId() {
                return "u999";
            }
        };
        setAuthentication(customPrincipal, Collections.<GrantedAuthority>emptyList());

        assertThat(adapter.currentUserId()).isEqualTo("u999");
    }

    private void setAuthentication(Object principal, Collection<? extends GrantedAuthority> authorities) {
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(principal);
        when(auth.getAuthorities()).thenAnswer(invocation -> authorities);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
