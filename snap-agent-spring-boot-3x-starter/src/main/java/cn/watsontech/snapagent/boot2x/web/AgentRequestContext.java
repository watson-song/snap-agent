package cn.watsontech.snapagent.boot2x.web;

/**
 * Thread-local context for the current agent HTTP request.
 *
 * <p>Populated by {@link SnapAgentFilter} from the {@link cn.watsontech.snapagent.core.security.SecurityGateway}
 * principal, then read by controller-side code on the same (servlet) thread.</p>
 *
 * <p><strong>HTTP-thread only.</strong> The agent execution loop runs on a
 * separate thread (the {@code taskExecutor} pool) where this ThreadLocal is
 * <em>not</em> propagated. Tool providers and the executor must obtain user
 * identity from {@link cn.watsontech.snapagent.core.tool.ToolContext}, never from
 * here.</p>
 *
 * <p>Must be {@link #clear() cleared} at the end of each request to avoid
 * thread-pool leakage.</p>
 */
public final class AgentRequestContext {

    private static final ThreadLocal<String> USER_ID = new ThreadLocal<String>();

    private AgentRequestContext() {
    }

    public static String getUserId() {
        return USER_ID.get();
    }

    public static void setUserId(String userId) {
        USER_ID.set(userId);
    }

    public static void clear() {
        USER_ID.remove();
    }
}
