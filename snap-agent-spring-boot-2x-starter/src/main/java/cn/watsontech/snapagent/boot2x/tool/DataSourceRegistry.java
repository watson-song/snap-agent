package cn.watsontech.snapagent.boot2x.tool;

import javax.sql.DataSource;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Registry of multi-environment {@link DataSource} instances (v0.6).
 *
 * <p>Each environment (e.g. {@code sit}, {@code uat}) maps to a pre-built
 * {@link DataSource}. When a skill invokes the {@code mysql_query} tool with an
 * {@code env} parameter, the tool calls {@link #resolve(String)} to obtain the
 * correct connection.</p>
 *
 * <p>If the caller passes a blank environment, the registry falls back to
 * {@code defaultEnv}; when {@code defaultEnv} is itself blank, the first
 * registered environment (insertion order) is used. An unknown environment name
 * results in an {@link IllegalArgumentException} so the LLM receives a clear
 * error rather than a silent fallback.</p>
 */
public class DataSourceRegistry {

    private final Map<String, DataSource> dataSources;
    private final String defaultEnv;

    /**
     * @param dataSources non-empty map of env name to DataSource (insertion-ordered)
     * @param defaultEnv  default environment name; blank = use first entry
     */
    public DataSourceRegistry(Map<String, DataSource> dataSources, String defaultEnv) {
        if (dataSources == null || dataSources.isEmpty()) {
            throw new IllegalArgumentException("dataSources must not be null or empty");
        }
        this.dataSources = Collections.unmodifiableMap(
                new LinkedHashMap<String, DataSource>(dataSources));
        this.defaultEnv = defaultEnv != null ? defaultEnv : "";
    }

    /**
     * Resolves the DataSource for the given environment.
     *
     * @param env environment name; null/blank = defaultEnv (or first if defaultEnv blank)
     * @return the matching DataSource
     * @throws IllegalArgumentException if the environment name is not registered
     */
    public DataSource resolve(String env) {
        String key = env != null ? env.trim() : "";
        if (key.isEmpty()) {
            key = defaultEnv != null ? defaultEnv.trim() : "";
        }
        if (key.isEmpty()) {
            // Use the first entry (insertion order)
            return dataSources.values().iterator().next();
        }
        DataSource ds = dataSources.get(key);
        if (ds == null) {
            throw new IllegalArgumentException("unknown datasource environment: " + key);
        }
        return ds;
    }

    /** Number of registered environments. */
    public int size() {
        return dataSources.size();
    }

    /** Available environment names (insertion order). */
    public Set<String> envNames() {
        return dataSources.keySet();
    }
}
