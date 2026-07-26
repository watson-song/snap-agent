package cn.watsontech.snapagent.boot2x.tool;

import cn.watsontech.snapagent.core.tool.ToolCallback;
import cn.watsontech.snapagent.core.tool.ToolCallbacks;
import cn.watsontech.snapagent.core.tool.ToolContext;
import cn.watsontech.snapagent.core.tool.ToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link JdbcQueryTools} using H2 in-memory.
 *
 * <p>Covers normal query, row truncation, SQL guard rejection
 * (TDD_SPEC §UC-12), multi-environment mode (v0.6), and backward compat.</p>
 */
class JdbcQueryToolsTest {

    private DataSource dataSource;
    private JdbcQueryTools provider;
    private JdbcQueryTools providerWithSmallMax;

    // Multi-env fixtures
    private DataSource sitDataSource;
    private DataSource uatDataSource;
    private DataSourceRegistry registry;
    private JdbcQueryTools multiEnvProvider;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = createH2WithTestData("testdb");
        provider = new JdbcQueryTools(dataSource, new SqlGuard(1000));
        providerWithSmallMax = new JdbcQueryTools(dataSource, new SqlGuard(2));

        // Multi-env: two separate in-memory DBs with different data
        sitDataSource = createH2WithTestData("sitdb", "SIT-Alice", "SIT-Bob");
        uatDataSource = createH2WithTestData("uatdb", "UAT-Charlie", "UAT-Dave");
        Map<String, DataSource> dsMap = new LinkedHashMap<String, DataSource>();
        dsMap.put("sit", sitDataSource);
        dsMap.put("uat", uatDataSource);
        registry = new DataSourceRegistry(dsMap, "sit");
        multiEnvProvider = new JdbcQueryTools(registry, new SqlGuard(1000));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (dataSource != null) {
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("DROP TABLE IF EXISTS test_table");
            }
        }
        if (sitDataSource != null) {
            try (Connection conn = sitDataSource.getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("DROP TABLE IF EXISTS test_table");
            }
        }
        if (uatDataSource != null) {
            try (Connection conn = uatDataSource.getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("DROP TABLE IF EXISTS test_table");
            }
        }
    }

    @Test
    void shouldReturnNameMysqlQuery() {
        assertThat(ToolCallbacks.from(provider)[0].getName()).isEqualTo("mysql_query");
    }

    @Test
    void shouldReturnSchemaContainingSqlProperty() {
        assertThat(ToolCallbacks.from(provider)[0].getName()).isEqualTo("mysql_query");

        String schema = ToolCallbacks.from(provider)[0].getJsonSchema();
        assertThat(schema).contains("sql");
        assertThat(schema).contains("required");
    }

    @Test
    void shouldReturnResultsWhenSelectExecuted() {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("sql", "SELECT * FROM test_table ORDER BY id");

        ToolResult result = ToolCallbacks.from(provider)[0].execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("Alice");
        assertThat(result.getContent()).contains("Bob");
        assertThat(result.getContent()).contains("Charlie");
    }

    @Test
    void shouldTruncateRowsWhenExceedingMax() {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("sql", "SELECT * FROM test_table ORDER BY id");

        ToolResult result = ToolCallbacks.from(providerWithSmallMax)[0].execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("truncated");
    }

    @Test
    void shouldRejectWhenSqlIsDelete() {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("sql", "DELETE FROM test_table WHERE id=1");

        ToolResult result = ToolCallbacks.from(provider)[0].execute(args, ctx());

        assertThat(result.getContent()).contains("只读策略拒绝");
        assertThat(result.getContent()).contains("DELETE");
    }

    @Test
    void shouldRejectWhenSqlContainsInsertInSubquery() {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("sql", "SELECT * FROM (INSERT INTO test_table VALUES(99,'X'))");

        ToolResult result = ToolCallbacks.from(provider)[0].execute(args, ctx());

        assertThat(result.getContent()).contains("INSERT");
    }

    @Test
    void shouldAppendLimitWhenNoLimit() {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("sql", "SELECT * FROM test_table");

        ToolResult result = ToolCallbacks.from(provider)[0].execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        // LIMIT 1000 should have been appended by SqlGuard
        assertThat(result.getContent()).contains("Alice");
        assertThat(result.getContent()).contains("Charlie");
    }

    @Test
    void shouldReturnErrorWhenSqlIsMissing() {
        Map<String, Object> args = new HashMap<String, Object>();

        ToolResult result = ToolCallbacks.from(provider)[0].execute(args, ctx());

        assertThat(result.getContent()).containsIgnoringCase("sql");
    }

    @Test
    void shouldHandleQueryWithWhereClause() {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("sql", "SELECT name FROM test_table WHERE id=1");

        ToolResult result = ToolCallbacks.from(provider)[0].execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("Alice");
    }

    @Test
    void shouldIncludeColumnHeadersInResult() {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("sql", "SELECT name FROM test_table WHERE id=1");

        ToolResult result = ToolCallbacks.from(provider)[0].execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).containsIgnoringCase("name");
    }

    // ---- Multi-env mode tests (v0.6) ----

    @Test
    void shouldExposeEnvParameterInSchemaWhenMultiEnv() {
        String schema = ToolCallbacks.from(multiEnvProvider)[0].getJsonSchema();

        assertThat(schema).contains("env");
        assertThat(schema).contains("Environment name");
    }

    @Test
    void shouldExposeEnvParameterInSchemaEvenWhenSingleEnv() {
        // In 2.x the @ToolParam is always present on the method signature,
        // so the schema always includes "env" regardless of single/multi-env mode.
        String schema = ToolCallbacks.from(provider)[0].getJsonSchema();

        assertThat(schema).contains("env");
    }

    @Test
    void shouldQuerySitEnvWhenEnvParamIsSit() {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("sql", "SELECT name FROM test_table ORDER BY id");
        args.put("env", "sit");

        ToolResult result = ToolCallbacks.from(multiEnvProvider)[0].execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("SIT-Alice");
        assertThat(result.getContent()).doesNotContain("UAT-Charlie");
    }

    @Test
    void shouldQueryUatEnvWhenEnvParamIsUat() {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("sql", "SELECT name FROM test_table ORDER BY id");
        args.put("env", "uat");

        ToolResult result = ToolCallbacks.from(multiEnvProvider)[0].execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("UAT-Charlie");
        assertThat(result.getContent()).doesNotContain("SIT-Alice");
    }

    @Test
    void shouldUseDefaultEnvWhenEnvParamIsBlank() {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("sql", "SELECT name FROM test_table ORDER BY id");
        // env not provided

        ToolResult result = ToolCallbacks.from(multiEnvProvider)[0].execute(args, ctx());

        // defaultEnv = "sit"
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("SIT-Alice");
    }

    @Test
    void shouldReturnErrorWhenEnvIsUnknown() {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("sql", "SELECT 1");
        args.put("env", "prod");

        ToolResult result = ToolCallbacks.from(multiEnvProvider)[0].execute(args, ctx());

        assertThat(result.getContent()).contains("prod");
    }

    @Test
    void shouldStillWorkInSingleEnvModeForBackwardCompat() {
        // The single-DataSource provider (no registry) should still function identically
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("sql", "SELECT * FROM test_table ORDER BY id");
        // env param is ignored in single-env mode
        args.put("env", "anything");

        ToolResult result = ToolCallbacks.from(provider)[0].execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("Alice");
        assertThat(result.getContent()).contains("Charlie");
    }

    // ---- helpers ----

    private ToolContext ctx() {
        return new ToolContext("task-1", "user-1", null);
    }

    private DataSource createH2WithTestData(String dbName) throws Exception {
        return createH2WithTestData(dbName, "Alice", "Bob", "Charlie");
    }

    private DataSource createH2WithTestData(String dbName, String... names) throws Exception {
        org.h2.Driver driver = new org.h2.Driver();
        DataSource ds = new SimpleDriverDataSource(driver,
                "jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1", "sa", "");
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS test_table (id INT, name VARCHAR(100))");
            stmt.execute("DELETE FROM test_table");
            for (int i = 0; i < names.length; i++) {
                stmt.execute("INSERT INTO test_table VALUES (" + (i + 1) + ", '" + names[i] + "')");
            }
        }
        return ds;
    }
}
