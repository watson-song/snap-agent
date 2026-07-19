package cn.watsontech.snapagent.boot2x.tool;

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
 * Integration tests for {@link JdbcQueryToolProvider} using H2 in-memory.
 *
 * <p>Covers normal query, row truncation, SQL guard rejection
 * (TDD_SPEC §UC-12), multi-environment mode (v0.6), and backward compat.</p>
 */
class JdbcQueryToolProviderTest {

    private DataSource dataSource;
    private JdbcQueryToolProvider provider;
    private JdbcQueryToolProvider providerWithSmallMax;

    // Multi-env fixtures
    private DataSource sitDataSource;
    private DataSource uatDataSource;
    private DataSourceRegistry registry;
    private JdbcQueryToolProvider multiEnvProvider;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = createH2WithTestData("testdb");
        provider = new JdbcQueryToolProvider(dataSource, new SqlGuard(1000));
        providerWithSmallMax = new JdbcQueryToolProvider(dataSource, new SqlGuard(2));

        // Multi-env: two separate in-memory DBs with different data
        sitDataSource = createH2WithTestData("sitdb", "SIT-Alice", "SIT-Bob");
        uatDataSource = createH2WithTestData("uatdb", "UAT-Charlie", "UAT-Dave");
        Map<String, DataSource> dsMap = new LinkedHashMap<String, DataSource>();
        dsMap.put("sit", sitDataSource);
        dsMap.put("uat", uatDataSource);
        registry = new DataSourceRegistry(dsMap, "sit");
        multiEnvProvider = new JdbcQueryToolProvider(registry, new SqlGuard(1000));
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

    // ---- Multi-env mode tests (v0.6) ----

    @Test
    void shouldExposeEnvParameterInSchemaWhenMultiEnv() {
        String schema = multiEnvProvider.schema();

        assertThat(schema).contains("env");
        assertThat(schema).contains("Environment name");
    }

    @Test
    void shouldNotExposeEnvParameterInSchemaWhenSingleEnv() {
        String schema = provider.schema();

        assertThat(schema).doesNotContain("\"env\"");
    }

    @Test
    void shouldQuerySitEnvWhenEnvParamIsSit() {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("sql", "SELECT name FROM test_table ORDER BY id");
        args.put("env", "sit");

        ToolResult result = multiEnvProvider.execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("SIT-Alice");
        assertThat(result.getContent()).doesNotContain("UAT-Charlie");
    }

    @Test
    void shouldQueryUatEnvWhenEnvParamIsUat() {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("sql", "SELECT name FROM test_table ORDER BY id");
        args.put("env", "uat");

        ToolResult result = multiEnvProvider.execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("UAT-Charlie");
        assertThat(result.getContent()).doesNotContain("SIT-Alice");
    }

    @Test
    void shouldUseDefaultEnvWhenEnvParamIsBlank() {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("sql", "SELECT name FROM test_table ORDER BY id");
        // env not provided

        ToolResult result = multiEnvProvider.execute(args, ctx());

        // defaultEnv = "sit"
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("SIT-Alice");
    }

    @Test
    void shouldReturnErrorWhenEnvIsUnknown() {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("sql", "SELECT 1");
        args.put("env", "prod");

        ToolResult result = multiEnvProvider.execute(args, ctx());

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).contains("prod");
    }

    @Test
    void shouldStillWorkInSingleEnvModeForBackwardCompat() {
        // The single-DataSource provider (no registry) should still function identically
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("sql", "SELECT * FROM test_table ORDER BY id");
        // env param is ignored in single-env mode
        args.put("env", "anything");

        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getRowCount()).isEqualTo(3);
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
