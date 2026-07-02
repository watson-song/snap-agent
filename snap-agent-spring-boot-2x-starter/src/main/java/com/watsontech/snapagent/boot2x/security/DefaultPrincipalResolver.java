package com.watsontech.snapagent.boot2x.security;

import com.watsontech.snapagent.core.security.PrincipalResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.userdetails.UserDetails;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Default {@link PrincipalResolver} implementation.
 *
 * <p>Resolution order (per TDD_SPEC §UC-19 and design doc 07 §5):</p>
 * <ol>
 *   <li>{@code principal instanceof String} → return directly.</li>
 *   <li>{@code principal instanceof UserDetails} → {@code getUsername()}.</li>
 *   <li>Reflection: try {@code getId()}, {@code getUserId()}, {@code getUsername()}
 *       (in that order); return the first non-null {@code String} result.</li>
 *   <li>All fail → return {@code null}.</li>
 * </ol>
 */
public class DefaultPrincipalResolver implements PrincipalResolver {

    private static final Logger log = LoggerFactory.getLogger(DefaultPrincipalResolver.class);

    private static final String[] REFLECTION_METHODS = {"getId", "getUserId", "getUsername"};

    @Override
    public String resolve(Object principal) {
        if (principal == null) {
            return null;
        }

        // 1. String principal
        if (principal instanceof String) {
            return (String) principal;
        }

        // 2. UserDetails principal
        if (principal instanceof UserDetails) {
            return ((UserDetails) principal).getUsername();
        }

        // 3. Reflection: getId / getUserId / getUsername
        for (String methodName : REFLECTION_METHODS) {
            String result = tryInvokeStringMethod(principal, methodName);
            if (result != null) {
                return result;
            }
        }

        // 4. Cannot resolve
        log.warn("Cannot resolve principal of type {}; consider configuring "
                + "snap-agent.security.principal-resolver-class", principal.getClass().getName());
        return null;
    }

    private String tryInvokeStringMethod(Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            Object result = method.invoke(target);
            if (result instanceof String) {
                return (String) result;
            }
            return null;
        } catch (NoSuchMethodException e) {
            return null;
        } catch (IllegalAccessException e) {
            return null;
        } catch (InvocationTargetException e) {
            log.debug("Method {}() threw exception on {}: {}", methodName,
                    target.getClass().getSimpleName(), e.getCause());
            return null;
        }
    }
}
