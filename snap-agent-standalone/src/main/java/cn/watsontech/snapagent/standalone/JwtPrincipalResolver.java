package cn.watsontech.snapagent.standalone;

import cn.watsontech.snapagent.core.security.PrincipalResolver;
import org.springframework.stereotype.Component;

/**
 * PrincipalResolver for standalone mode.
 *
 * <p>In standalone mode, the JWT filter sets the userId as the principal
 * in Spring Security's SecurityContext. This resolver simply extracts it.</p>
 *
 * <p>The {@code principal} parameter is the userId string set by
 * {@link JwtAuthenticationFilter} as the principal of
 * {@code UsernamePasswordAuthenticationToken}.</p>
 */
@Component
public class JwtPrincipalResolver implements PrincipalResolver {

    @Override
    public String resolve(Object principal) {
        if (principal == null) {
            return null;
        }
        // In standalone mode, principal is the userId string from JWT
        return principal.toString();
    }
}
