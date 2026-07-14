package cn.watsontech.snapagent.boot2x.security;

import cn.watsontech.snapagent.core.security.PrincipalResolver;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ShiroAdapter}.
 *
 * <p>Uses Shiro's {@link ThreadContext} to bind a mock {@link Subject} for
 * testing (TDD_SPEC §UC-18).</p>
 */
class ShiroAdapterTest {

    private PrincipalResolver principalResolver;
    private ShiroAdapter adapter;

    @BeforeEach
    void setUp() {
        ThreadContext.remove();
        principalResolver = new DefaultPrincipalResolver();
        adapter = new ShiroAdapter(principalResolver);
    }

    @AfterEach
    void tearDown() {
        ThreadContext.remove();
    }

    @Test
    void shouldReturnUserIdWhenPrincipalIsString() {
        Subject subject = mock(Subject.class);
        when(subject.getPrincipal()).thenReturn("user001");
        ThreadContext.bind(subject);

        assertThat(adapter.currentUserId()).isEqualTo("user001");
    }

    @Test
    void shouldReturnNullWhenPrincipalIsNull() {
        Subject subject = mock(Subject.class);
        when(subject.getPrincipal()).thenReturn(null);
        ThreadContext.bind(subject);

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
    void shouldReturnTrueWhenSubjectIsPermitted() {
        Subject subject = mock(Subject.class);
        when(subject.isPermitted("skills:run")).thenReturn(true);
        ThreadContext.bind(subject);

        assertThat(adapter.hasPermission("skills:run")).isTrue();
    }

    @Test
    void shouldReturnFalseWhenSubjectNotPermitted() {
        Subject subject = mock(Subject.class);
        when(subject.isPermitted("skills:admin")).thenReturn(false);
        ThreadContext.bind(subject);

        assertThat(adapter.hasPermission("skills:admin")).isFalse();
    }

    @Test
    void shouldUseWildcardMatchingViaShiro() {
        Subject subject = mock(Subject.class);
        // Shiro wildcard: skills:* matches skills:run
        when(subject.isPermitted("skills:run")).thenReturn(true);
        ThreadContext.bind(subject);

        // The adapter delegates to Subject.isPermitted which does wildcard matching
        assertThat(adapter.hasPermission("skills:run")).isTrue();
    }

    @Test
    void shouldResolveCustomPrincipalViaReflection() {
        Object customPrincipal = new Object() {
            public String getId() {
                return "u777";
            }
        };
        Subject subject = mock(Subject.class);
        when(subject.getPrincipal()).thenReturn(customPrincipal);
        ThreadContext.bind(subject);

        assertThat(adapter.currentUserId()).isEqualTo("u777");
    }
}
