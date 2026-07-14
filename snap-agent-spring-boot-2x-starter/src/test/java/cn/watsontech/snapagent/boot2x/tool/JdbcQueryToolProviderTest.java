package cn.watsontech.snapagent.boot2x.tool;

import cn.watsontech.snapagent.core.tool.ToolContext;
import cn.watsontech.snapagent.core.tool.ToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link JdbcQueryToolProvider} using H2 in-memory.
 *
 * <p>Covers normal query, row truncation, and SQL guard rejection
 * (TDD_SPEC §UC-12).</p>
 */
class JdbcQueryToolProviderTest {

    private DataSource dataSource;
    private JdbcQueryToolProvider provider;
    private JdbcQueryToolProvider providerWithSmallMax;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = createH2WithTestData();
        provider = new JdbcQueryToolProvider(dataSource, new SqlGuard(1000));
        providerWithSmallMax = new JdbcQueryToolProvider(dataSource, new SqlGuard(2));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (dataSource != null) {
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("DROP TABLE IF EXISTS test_table");
            }
        }
    }

    @Test
    void shouldReturnNameMysqlQuery() {
        assertThat(provider.name()).isEqualTo("mysql_query");
    }

    @Test
    void shouldReturnSchemaContainingSqlProperty() {
        String schema = provider.schema();

        assertThat(schema).contains("mysql_query");
        assertThat(schema).contains("sql");
        assertThat(schema).contains("required");
    }

    @Test
    void shouldReturnResultsWhenSelectExecuted() {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("sql", "SELECT * FROM test_table ORDER BY id");

        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getRowCount()).isEqualTo(3);
        assertThat(result.getContent()).contains("Alice");
        assertThat(result.getContent()).contains("Bob");
        assertThat(result.getContent()).contains("Charlie");
    }

    @Test
    void shouldTruncateRowsWhenExceedingMax() {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("sql", "SELECT * FROM test_table ORDER BY id");

        ToolResult result = providerWithSmallMax.execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getRowCount()).isEqualTo(2);
        assertThat(result.isTruncated()).isTrue();
    }

    @Test
    void shouldRejectWhenSqlIsDelete() {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("sql", "DELETE FROM test_table WHERE id=1");

        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).contains("只读策略拒绝");
        assertThat(result.getError()).contains("DELETE");
    }

    @Test
    void shouldRejectWhenSqlContainsInsertInSubquery() {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("sql", "SELECT * FROM (INSERT INTO test_table VALUES(99,'X'))");

        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).contains("INSERT");
    }

    @Test
    void shouldAppendLimitWhenNoLimit() {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("sql", "SELECT * FROM test_table");

        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        // LIMIT 1000 should have been appended by SqlGuard
        assertThat(result.getRowCount()).isEqualTo(3);
    }

    @Test
    void shouldReturnErrorWhenSqlIsMissing() {
        Map<String, Object> args = new HashMap<String, Object>();

        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).contains("sql");
    }

    @Test
    void shouldHandleQueryWithWhereClause() {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("sql", "SELECT name FROM test_table WHERE id=1");

        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getRowCount()).isEqualTo(1);
        assertThat(result.getContent()).contains("Alice");
    }

    @Test
    void shouldIncludeColumnHeadersInResult() {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("sql", "SELECT name FROM test_table WHERE id=1");

        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).containsIgnoringCase("name");
    }

    // ---- helpers ----

    private ToolContext ctx() {
        return new ToolContext("task-1", "user-1", null);
    }

    private DataSource createH2WithTestData() throws Exception {
        org.h2.jdbcx.JdbcConnectionPool pool =
                org.h2.jdbcx.JdbcConnectionPool.create("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1", "sa", "");
        try (Connection conn = pool.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS test_table (id INT, name VARCHAR(100))");
            stmt.execute("DELETE FROM test_table");
            stmt.execute("INSERT INTO test_table VALUES (1, 'Alice')");
            stmt.execute("INSERT INTO test_table VALUES (2, 'Bob')");
            stmt.execute("INSERT INTO test_table VALUES (3, 'Charlie')");
        }
        return pool;
    }
}
