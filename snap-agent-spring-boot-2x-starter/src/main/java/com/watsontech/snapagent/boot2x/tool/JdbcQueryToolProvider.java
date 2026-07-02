package com.watsontech.snapagent.boot2x.tool;

import com.watsontech.snapagent.core.tool.ToolContext;
import com.watsontech.snapagent.core.tool.ToolProvider;
import com.watsontech.snapagent.core.tool.ToolResult;
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
 * <p>Results are formatted as a pipe-delimited table and truncated to
 * {@code maxResultRows}. Each invocation is audited via
 * {@link ToolContext#getAuditCallback()} (if present).</p>
 */
public class JdbcQueryToolProvider implements ToolProvider {

    private static final Logger log = LoggerFactory.getLogger(JdbcQueryToolProvider.class);

    private static final String SCHEMA = "{\"name\":\"mysql_query\","
            + "\"description\":\"Execute a read-only SQL query.\","
            + "\"input_schema\":{\"type\":\"object\","
            + "\"properties\":{\"sql\":{\"type\":\"string\","
            + "\"description\":\"SELECT/SHOW/DESCRIBE/EXPLAIN/WITH query\"}},"
            + "\"required\":[\"sql\"]}}";

    private final DataSource dataSource;
    private final SqlGuard sqlGuard;

    public JdbcQueryToolProvider(DataSource dataSource, SqlGuard sqlGuard) {
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource must not be null");
        }
        if (sqlGuard == null) {
            throw new IllegalArgumentException("sqlGuard must not be null");
        }
        this.dataSource = dataSource;
        this.sqlGuard = sqlGuard;
    }

    @Override
    public String name() {
        return "mysql_query";
    }

    @Override
    public String schema() {
        return SCHEMA;
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

        log.info("Executing SQL (sanitized): {}", sanitizedSql);

        try (Connection conn = dataSource.getConnection();
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
