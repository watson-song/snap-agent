package cn.watsontech.snapagent.boot2x.tool;

import cn.watsontech.snapagent.core.tool.Tool;
import cn.watsontech.snapagent.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;

/**
 * 2.x read-only SQL query tools exposed via {@code @Tool} annotation methods.
 *
 * <p>Refactored from the 1.x {@code JdbcQueryToolProvider} (which implemented the
 * now-removed {@code ToolProvider} SPI) to a {@code @Tool} method discovered by
 * {@link cn.watsontech.snapagent.core.tool.ToolCallbacks#from(Object)}.</p>
 *
 * <p>Delegates SQL safety to {@link SqlGuard} (whitelist, blacklist, multi-statement
 * rejection, LIMIT injection). Supports multi-environment mode when constructed
 * with a {@link DataSourceRegistry}.</p>
 */
public class JdbcQueryTools {

    private static final Logger log = LoggerFactory.getLogger(JdbcQueryTools.class);

    private final DataSource dataSource;
    private final DataSourceRegistry registry;
    private final SqlGuard sqlGuard;

    public JdbcQueryTools(DataSource dataSource, SqlGuard sqlGuard) {
        if (dataSource == null) throw new IllegalArgumentException("dataSource must not be null");
        if (sqlGuard == null) throw new IllegalArgumentException("sqlGuard must not be null");
        this.dataSource = dataSource;
        this.registry = null;
        this.sqlGuard = sqlGuard;
    }

    public JdbcQueryTools(DataSourceRegistry registry, SqlGuard sqlGuard) {
        if (registry == null) throw new IllegalArgumentException("registry must not be null");
        if (sqlGuard == null) throw new IllegalArgumentException("sqlGuard must not be null");
        this.dataSource = null;
        this.registry = registry;
        this.sqlGuard = sqlGuard;
    }

    @Tool(name = "mysql_query", description = "Execute a read-only SQL query (SELECT/SHOW/DESCRIBE/EXPLAIN/WITH).")
    public String query(
            @ToolParam(description = "SELECT/SHOW/DESCRIBE/EXPLAIN/WITH query") String sql,
            @ToolParam(description = "Environment name (e.g. sit/uat). Empty=use default.", required = false) String env) {
        SqlGuard.Result guardResult = sqlGuard.validate(sql);
        if (!guardResult.isAllowed()) {
            log.warn("SQL rejected by guard: {}", guardResult.getReason());
            return "Error: " + guardResult.getReason();
        }

        String sanitizedSql = guardResult.getSql();
        int maxRows = sqlGuard.getMaxResultRows();

        DataSource targetDs;
        if (registry != null) {
            String envName = env != null ? env : "";
            try {
                targetDs = registry.resolve(envName);
            } catch (IllegalArgumentException e) {
                log.warn("DataSource resolution failed for env '{}': {}", envName, e.getMessage());
                return "Error: " + e.getMessage();
            }
            log.info("Executing SQL (sanitized) on env '{}': {}", envName, sanitizedSql);
        } else {
            targetDs = dataSource;
            log.info("Executing SQL (sanitized): {}", sanitizedSql);
        }

        try (Connection conn = targetDs.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.setMaxRows(maxRows);
            try (ResultSet rs = stmt.executeQuery(sanitizedSql)) {
                return formatResult(rs, maxRows);
            }
        } catch (RuntimeException e) {
            log.error("SQL execution failed: {}", e.getMessage());
            return "Error: SQL execution failed: " + e.getMessage();
        } catch (java.sql.SQLException e) {
            log.error("SQL execution failed: {}", e.getMessage());
            return "Error: SQL execution failed: " + e.getMessage();
        }
    }

    private String formatResult(ResultSet rs, int maxRows) throws java.sql.SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int columnCount = meta.getColumnCount();

        StringBuilder sb = new StringBuilder();
        for (int c = 1; c <= columnCount; c++) {
            if (c > 1) sb.append(" | ");
            sb.append(meta.getColumnLabel(c));
        }
        sb.append("\n");

        int rowCount = 0;
        while (rs.next()) {
            rowCount++;
            for (int c = 1; c <= columnCount; c++) {
                if (c > 1) sb.append(" | ");
                String val = rs.getString(c);
                sb.append(val != null ? val : "NULL");
            }
            sb.append("\n");
        }

        if (rowCount >= maxRows) {
            sb.append("\n...[truncated, total ").append(rowCount).append(" rows]");
        }
        return sb.toString();
    }
}
