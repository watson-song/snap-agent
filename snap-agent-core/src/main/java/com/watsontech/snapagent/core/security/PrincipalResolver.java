package com.watsontech.snapagent.core.security;

/**
 * Resolves an authentication principal object to a user id string.
 *
 * <p>Principal types are application-specific (String username,
 * {@code UserDetails}, custom {@code User} object, etc.). This SPI decouples
 * the agent library from the host's security model.</p>
 *
 * <p>The default implementation lives in the starter module
 * ({@code DefaultPrincipalResolver}); hosts may provide a custom
 * implementation via {@code snap-agent.security.principal-resolver-class}.</p>
 */
public interface PrincipalResolver {

    /**
     * Resolve the principal to a user id.
     *
     * @param principal the principal object from the security framework
     * @return the user id, or {@code null} if resolution fails
     */
    String resolve(Object principal);
}
