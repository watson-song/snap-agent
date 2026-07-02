package com.watsontech.snapagent.boot2x.security;

import com.watsontech.snapagent.core.security.PrincipalResolver;
import com.watsontech.snapagent.core.security.SecurityGateway;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.subject.Subject;

/**
 * {@link SecurityGateway} adapter for Apache Shiro.
 *
 * <p>Assembled when {@code SecurityUtils} is on the classpath
 * (via {@code @ConditionalOnClass} on the bean method in AutoConfiguration).</p>
 *
 * <ul>
 *   <li>{@code currentUserId()} — reads {@code SecurityUtils.getSubject().getPrincipal()}
 *       and resolves it via {@link PrincipalResolver}.</li>
 *   <li>{@code hasPermission(code)} — empty code returns true; otherwise
 *       delegates to {@code Subject.isPermitted(code)} which uses Shiro's
 *       <strong>wildcard</strong> permission matching.</li>
 * </ul>
 */
public class ShiroAdapter implements SecurityGateway {

    private final PrincipalResolver principalResolver;

    public ShiroAdapter(PrincipalResolver principalResolver) {
        this.principalResolver = principalResolver;
    }

    @Override
    public String currentUserId() {
        try {
            Subject subject = SecurityUtils.getSubject();
            if (subject == null) {
                return null;
            }
            Object principal = subject.getPrincipal();
            return principalResolver.resolve(principal);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public boolean hasPermission(String code) {
        if (code == null || code.isEmpty()) {
            return true;
        }
        try {
            Subject subject = SecurityUtils.getSubject();
            if (subject == null) {
                return false;
            }
            return subject.isPermitted(code);
        } catch (RuntimeException e) {
            return false;
        }
    }
}
