package com.watsontech.snapagent.boot2x.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DefaultPrincipalResolver}.
 *
 * <p>Covers String principal, UserDetails principal, reflection (getId /
 * getUserId / getUsername), and failure case (TDD_SPEC §UC-19).</p>
 */
class DefaultPrincipalResolverTest {

    private final DefaultPrincipalResolver resolver = new DefaultPrincipalResolver();

    @Test
    void shouldReturnStringWhenPrincipalIsString() {
        String result = resolver.resolve("user001");

        assertThat(result).isEqualTo("user001");
    }

    @Test
    void shouldReturnNullWhenPrincipalIsNull() {
        String result = resolver.resolve(null);

        assertThat(result).isNull();
    }

    @Test
    void shouldReturnUsernameWhenPrincipalIsUserDetails() {
        UserDetails userDetails = mock(UserDetails.class);
        when(userDetails.getUsername()).thenReturn("admin");

        String result = resolver.resolve(userDetails);

        assertThat(result).isEqualTo("admin");
    }

    @Test
    void shouldReturnIdWhenPrincipalHasGetIdMethod() throws Exception {
        // Use a simple object with getId() via reflection
        Object principal = new Object() {
            public String getId() {
                return "123";
            }
        };

        String result = resolver.resolve(principal);

        assertThat(result).isEqualTo("123");
    }

    @Test
    void shouldReturnUserIdWhenPrincipalHasGetUserIdMethod() {
        Object principal = new Object() {
            public String getUserId() {
                return "u456";
            }
        };

        String result = resolver.resolve(principal);

        assertThat(result).isEqualTo("u456");
    }

    @Test
    void shouldReturnUsernameWhenPrincipalHasGetUsernameMethod() {
        Object principal = new Object() {
            public String getUsername() {
                return "alice";
            }
        };

        String result = resolver.resolve(principal);

        assertThat(result).isEqualTo("alice");
    }

    @Test
    void shouldPreferGetIdOverGetUserId() {
        Object principal = new Object() {
            public String getId() {
                return "from-getId";
            }

            public String getUserId() {
                return "from-getUserId";
            }
        };

        String result = resolver.resolve(principal);

        assertThat(result).isEqualTo("from-getId");
    }

    @Test
    void shouldPreferGetUserIdOverGetUsername() {
        Object principal = new Object() {
            public String getUserId() {
                return "from-getUserId";
            }

            public String getUsername() {
                return "from-getUsername";
            }
        };

        String result = resolver.resolve(principal);

        assertThat(result).isEqualTo("from-getUserId");
    }

    @Test
    void shouldReturnNullWhenNoResolvableMethod() {
        Object principal = new Object();

        String result = resolver.resolve(principal);

        assertThat(result).isNull();
    }

    @Test
    void shouldReturnNullWhenGetIdReturnsNull() {
        Object principal = new Object() {
            public String getId() {
                return null;
            }

            public String getUserId() {
                return "fallback";
            }
        };

        String result = resolver.resolve(principal);

        assertThat(result).isEqualTo("fallback");
    }

    @Test
    void shouldReturnNullWhenAllReflectionMethodsReturnNull() {
        Object principal = new Object() {
            public String getId() {
                return null;
            }

            public String getUserId() {
                return null;
            }

            public String getUsername() {
                return null;
            }
        };

        String result = resolver.resolve(principal);

        assertThat(result).isNull();
    }

    @Test
    void shouldSkipNonStringGetId() {
        Object principal = new Object() {
            public Long getId() {
                return 42L;
            }

            public String getUserId() {
                return "string-id";
            }
        };

        String result = resolver.resolve(principal);

        // getId returns Long (not String), should fall through to getUserId
        assertThat(result).isEqualTo("string-id");
    }

    @Test
    void shouldHandleGetIdThrowingException() {
        Object principal = new Object() {
            public String getId() {
                throw new RuntimeException("boom");
            }

            public String getUserId() {
                return "fallback";
            }
        };

        String result = resolver.resolve(principal);

        assertThat(result).isEqualTo("fallback");
    }
}
