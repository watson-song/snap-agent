package cn.watsontech.snapagent.standalone;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JwtPrincipalResolverTest {

    private final JwtPrincipalResolver resolver = new JwtPrincipalResolver();

    @Test
    void shouldResolveStringPrincipal() {
        assertThat(resolver.resolve("user123")).isEqualTo("user123");
    }

    @Test
    void shouldReturnNullForNullPrincipal() {
        assertThat(resolver.resolve(null)).isNull();
    }

    @Test
    void shouldConvertObjectToString() {
        assertThat(resolver.resolve(456L)).isEqualTo("456");
    }
}
