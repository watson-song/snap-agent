package cn.watsontech.snapagent.core.security;

/**
 * Security gateway SPI — bridges the agent library to the host's security
 * framework (Spring Security or Shiro).
 *
 * <p>Implementations live in the starter module:
 * {@code SpringSecurityAdapter} and {@code ShiroAdapter}, selected via
 * {@code @ConditionalOnClass}.</p>
 *
 * <p>Authentication is delegated to the host; this interface only reads
 * the already-authenticated principal and checks permissions.</p>
 */
public interface SecurityGateway {

    /**
     * Return the current authenticated user id.
     *
     * @return the user id, or {@code null} if not authenticated
     */
    String currentUserId();

    /**
     * Return the current authenticated user's display name (real name /
     * nickname). Used for UI display; {@link #currentUserId()} is still
     * used for ownership and audit. Implementations should return
     * {@code null} when a display name is not available — the controller
     * will fall back to the user id.
     *
     * @return display name, or {@code null} if unavailable
     */
    default String currentUserName() {
        return null;
    }

    /**
     * Check whether the current user has the given permission code.
     *
     * @param code permission code; when empty, returns {@code true}
     * @return {@code true} if the user has the permission
     */
    boolean hasPermission(String code);
}
