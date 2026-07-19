package cn.watsontech.snapagent.boot2x.tool;

import cn.watsontech.snapagent.core.tool.ToolContext;
import cn.watsontech.snapagent.core.tool.ToolProvider;
import cn.watsontech.snapagent.core.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.Map;

/**
 * {@link ToolProvider} implementation for read-only SQL queries via an
 * independent read-only {@link DataSource}.
 *
 * <p>Tool name: {@code mysql_query}. Delegates SQL safety to {@link SqlGuard}
 * (whitelist, blacklist, multi-statement rejection, LIMIT injection).</p>
 *
 * <p><b>Multi-environment mode (v0.6):</b> when constructed with a
 * {@link DataSourceRegistry}, the tool schema exposes an {@code env} parameter
 * so the LLM can select the target environment (e.g. sit/uat). The single-
 * DataSource constructor is retained for backward compatibility.</p>
 *
 * <p>Results are formatted as a pipe-delimited table and truncated to
 * {@code maxResultRows}. Each invocation is audited via
 * {@link ToolContext#getAuditCallback()} (if present).</p>
 */
public class JdbcQueryToolProvider implements ToolProvider {

    private static final Logger log = LoggerFactory.getLogger(JdbcQueryToolProvider.class);

    private static final String SCHEMA_SINGLE = "{\"name\":\"mysql_query\","
            + "\"description\":\"Execute a read-only SQL query.\","
            + "\"input_schema\":{\"type\":\"object\","
            + "\"properties\":{\"sql\":{\"type\":\"string\","
            + "\"description\":\"SELECT/SHOW/DESCRIBE/EXPLAIN/WITH query\"}},"
            + "\"required\":[\"sql\"]}}";

    private static final String SCHEMA_MULTI_ENV = "{\"name\":\"mysql_query\","
            + "\"description\":\"Execute a read-only SQL query.\","
            + "\"input_schema\":{\"type\":\"object\","
            + "\"properties\":{\"sql\":{\"type\":\"string\","
            + "\"description\":\"SELECT/SHOW/DESCRIBE/EXPLAIN/WITH query\"},"
            + "\"env\":{\"type\":\"string\","
            + "\"description\":\"Environment name (e.g. sit/uat). Empty=use default.\"}},"
            + "\"required\":[\"sql\"]}}";

    private final DataSource dataSource;
    private final DataSourceRegistry registry;
    private final SqlGuard sqlGuard;

    /**
     * Single-environment constructor (backward compatible).
     *
     * @param dataSource the read-only DataSource
     * @param sqlGuard   SQL safety guard
     */
    public JdbcQueryToolProvider(DataSource dataSource, SqlGuard sqlGuard) {
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource must not be null");
        }
        if (sqlGuard == null) {
            throw new IllegalArgumentException("sqlGuard must not be null");
        }
        this.dataSource = dataSource;
        this.registry = null;
        this.sqlGuard = sqlGuard;
    }

    /**
     * Multi-environment constructor (v0.6).
     *
     * @param registry multi-env DataSource registry
     * @param sqlGuard SQL safety guard
     */
    public JdbcQueryToolProvider(DataSourceRegistry registry, SqlGuard sqlGuard) {
        if (registry == null) {
            throw new IllegalArgumentException("registry must not be null");
        }
        if (sqlGuard == null) {
            throw new IllegalArgumentException("sqlGuard must not be null");
        }
        this.dataSource = null;
        this.registry = registry;
        this.sqlGuard = sqlGuard;
    }

    @Override
    public String name() {
        return "mysql_query";
    }

    @Override
    public String schema() {
        return registry != null ? SCHEMA_MULTI_ENV : SCHEMA_SINGLE;
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
        long start = System.currentTimeMillis();

        String sql = extractSql(args);
        if (sql == null) {
            return ToolResult.error("missing required parameter: sql", elapsed(start));
        }

        SqlGuard.Result guardResult = sqlGuard.validate(sql);
        if (!guardResult.isAllowed()) {
            String reason = guardResult.getReason();
            log.warn("SQL rejected by guard: {}", reason);
            return ToolResult.error(reason, elapsed(start));
        }

        String sanitizedSql = guardResult.getSql();
        int maxRows = sqlGuard.getMaxResultRows();

        // Resolve the target DataSource (multi-env or single)
        DataSource targetDs;
        if (registry != null) {
            String env = extractEnv(args);
            try {
                targetDs = registry.resolve(env);
            } catch (IllegalArgumentException e) {
                log.warn("DataSource resolution failed for env '{}': {}", env, e.getMessage());
                return ToolResult.error(e.getMessage(), elapsed(start));
            }
            log.info("Executing SQL (sanitized) on env '{}': {}", env, sanitizedSql);
        } else {
            targetDs = dataSource;
            log.info("Executing SQL (sanitized): {}", sanitizedSql);
        }

        try (Connection conn = targetDs.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.setMaxRows(maxRows);
            try (ResultSet rs = stmt.executeQuery(sanitizedSql)) {
                return formatResult(rs, maxRows, start);
            }
        } catch (RuntimeException e) {
            log.error("SQL execution failed: {}", e.getMessage());
            return ToolResult.error("SQL execution failed: " + e.getMessage(), elapsed(start));
        } catch (java.sql.SQLException e) {
            log.error("SQL execution failed: {}", e.getMessage());
            return ToolResult.error("SQL execution failed: " + e.getMessage(), elapsed(start));
        }
    }

    private String extractSql(Map<String, Object> args) {
        if (args == null) {
            return null;
        }
        Object value = args.get("sql");
        if (value instanceof String) {
            return (String) value;
        }
        return null;
    }

    private String extractEnv(Map<String, Object> args) {
        if (args == null) {
            return "";
        }
        Object value = args.get("env");
        if (value instanceof String) {
            return (String) value;
        }
        return "";
    }

    private ToolResult formatResult(ResultSet rs, int maxRows, long start) throws java.sql.SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int columnCount = meta.getColumnCount();

        StringBuilder sb = new StringBuilder();

        // Header
        for (int c = 1; c <= columnCount; c++) {
            if (c > 1) {
                sb.append(" | ");
            }
            sb.append(meta.getColumnLabel(c));
        }
        sb.append("\n");

        // Rows
        int rowCount = 0;
        while (rs.next()) {
            rowCount++;
            for (int c = 1; c <= columnCount; c++) {
                if (c > 1) {
                    sb.append(" | ");
                }
                String val = rs.getString(c);
                sb.append(val != null ? val : "NULL");
            }
            sb.append("\n");
        }

        boolean truncated = rowCount >= maxRows;
        long duration = elapsed(start);

        if (truncated) {
            return ToolResult.truncated(sb.toString(), rowCount, duration);
        }
        return ToolResult.success(sb.toString(), rowCount, duration);
    }

    private long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }
}
