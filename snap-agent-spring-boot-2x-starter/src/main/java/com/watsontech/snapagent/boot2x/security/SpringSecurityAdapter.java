package com.watsontech.snapagent.boot2x.security;

import com.watsontech.snapagent.core.security.PrincipalResolver;
import com.watsontech.snapagent.core.security.SecurityGateway;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collection;

/**
 * {@link SecurityGateway} adapter for Spring Security.
 *
 * <p>Assembled when {@code SecurityContextHolder} is on the classpath
 * (via {@code @ConditionalOnClass} on the bean method in AutoConfiguration).</p>
 *
 * <ul>
 *   <li>{@code currentUserId()} — reads {@code SecurityContextHolder} authentication
 *       principal and resolves it via {@link PrincipalResolver}.</li>
 *   <li>{@code hasPermission(code)} — empty code returns true; otherwise
 *       <strong>exact</strong> match against authorities (not wildcard).</li>
 * </ul>
 */
public class SpringSecurityAdapter implements SecurityGateway {

    private final PrincipalResolver principalResolver;

    public SpringSecurityAdapter(PrincipalResolver principalResolver) {
        this.principalResolver = principalResolver;
    }

    @Override
    public String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return null;
        }
        Object principal = auth.getPrincipal();
        return principalResolver.resolve(principal);
    }

    @Override
    public boolean hasPermission(String code) {
        if (code == null || code.isEmpty()) {
            return true;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        Collection<? extends GrantedAuthority> authorities = auth.getAuthorities();
        if (authorities == null) {
            return false;
        }
        for (GrantedAuthority authority : authorities) {
            if (code.equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}
