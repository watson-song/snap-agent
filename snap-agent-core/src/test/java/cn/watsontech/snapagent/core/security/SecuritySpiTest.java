package cn.watsontech.snapagent.core.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Simple tests verifying the {@link SecurityGateway} and {@link PrincipalResolver}
 * SPI interfaces can be implemented and behave correctly.
 *
 * <p>Concrete implementations (SpringSecurityAdapter, ShiroAdapter,
 * DefaultPrincipalResolver) live in the starter module.</p>
 */
class SecuritySpiTest {

    @Test
    void shouldBeImplementableWithAnonymousClass() {
        SecurityGateway gateway = new SecurityGateway() {
            @Override
            public String currentUserId() {
                return "user-001";
            }

            @Override
            public boolean hasPermission(String code) {
                return code == null || code.isEmpty() || "skills:run".equals(code);
            }
        };

        assertThat(gateway.currentUserId()).isEqualTo("user-001");
        assertThat(gateway.hasPermission("")).isTrue();
        assertThat(gateway.hasPermission(null)).isTrue();
        assertThat(gateway.hasPermission("skills:run")).isTrue();
        assertThat(gateway.hasPermission("skills:admin")).isFalse();
    }

    @Test
    void shouldReturnNullWhenUnauthenticated() {
        SecurityGateway gateway = new SecurityGateway() {
            @Override
            public String currentUserId() {
                return null;
            }

            @Override
            public boolean hasPermission(String code) {
                return false;
            }
        };

        assertThat(gateway.currentUserId()).isNull();
        assertThat(gateway.hasPermission("any")).isFalse();
    }

    @Test
    void shouldBeImplementableForPrincipalResolver() {
        PrincipalResolver resolver = new PrincipalResolver() {
            @Override
            public String resolve(Object principal) {
                if (principal instanceof String) {
                    return (String) principal;
                }
                return null;
            }
        };

        assertThat(resolver.resolve("user-001")).isEqualTo("user-001");
        assertThat(resolver.resolve(12345)).isNull();
        assertThat(resolver.resolve(null)).isNull();
    }

    // ---- GAP (P1): SecurityGateway.currentUserName default null ----

    @Test
    void currentUserName_shouldDefaultToNullWhenNotOverridden() {
        // The interface provides a default implementation returning null.
        // Implementations that don't override currentUserName() must inherit
        // this default — the controller then falls back to the user id.
        SecurityGateway gateway = new SecurityGateway() {
            @Override
            public String currentUserId() {
                return "user-001";
            }

            @Override
            public boolean hasPermission(String code) {
                return true;
            }
            // currentUserName() intentionally NOT overridden
        };

        assertThat(gateway.currentUserName()).isNull();
    }

    @Test
    void currentUserName_canBeOverriddenToProvideDisplayName() {
        SecurityGateway gateway = new SecurityGateway() {
            @Override
            public String currentUserId() {
                return "user-001";
            }

            @Override
            public String currentUserName() {
                return "Alice Wang";
            }

            @Override
            public boolean hasPermission(String code) {
                return true;
            }
        };

        assertThat(gateway.currentUserName()).isEqualTo("Alice Wang");
        // currentUserId is still the canonical id (not affected by display name)
        assertThat(gateway.currentUserId()).isEqualTo("user-001");
    }

    @Test
    void currentUserName_defaultNullAllowsControllerFallback() {
        // When currentUserName() returns null (default), the controller must
        // fall back to currentUserId(). This test verifies the SPI contract:
        // null display name + non-null user id → caller falls back.
        SecurityGateway gateway = new SecurityGateway() {
            @Override
            public String currentUserId() {
                return "user-001";
            }

            @Override
            public boolean hasPermission(String code) {
                return true;
            }
        };

        String displayName = gateway.currentUserName();
        String fallback = displayName != null ? displayName : gateway.currentUserId();

        assertThat(fallback).isEqualTo("user-001");
    }
}
