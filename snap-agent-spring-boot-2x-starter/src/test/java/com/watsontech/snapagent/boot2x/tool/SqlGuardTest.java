package com.watsontech.snapagent.boot2x.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SqlGuard} — the most critical safety component.
 *
 * <p>Covers all reject cases (DELETE/UPDATE/DROP/INSERT/CREATE/ALTER/TRUNCATE/
 * multi-statement/INTO OUTFILE/LOAD_FILE/SLEEP/RENAME/GRANT/REVOKE/REPLACE/
 * MERGE/CALL/HANDLER/LOCK/UNLOCK/FLUSH/RESET/SHUTDOWN/KILL/LOAD/BENCHMARK)
 * and pass cases (SELECT/WITH/SHOW/DESCRIBE/EXPLAIN/DESC + LIMIT injection/rewrite).</p>
 */
class SqlGuardTest {

    private final SqlGuard guard = new SqlGuard(1000);

    // ---- Reject: first keyword not in whitelist ----

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "DELETE FROM users WHERE id=1|DELETE",
        "UPDATE users SET name='x'|UPDATE",
        "DROP TABLE users|DROP",
        "INSERT INTO users VALUES(1)|INSERT",
        "TRUNCATE TABLE users|TRUNCATE",
        "CREATE TABLE x(id INT)|CREATE",
        "ALTER TABLE x ADD COLUMN y INT|ALTER",
        "RENAME TABLE x TO y|RENAME",
        "GRANT SELECT ON *.* TO 'x'@'localhost'|GRANT",
        "REVOKE ALL ON *.* FROM 'x'@'localhost'|REVOKE",
        "REPLACE INTO users VALUES(1)|REPLACE",
        "MERGE INTO users USING dual ON 1=1 WHEN MATCHED THEN UPDATE SET name='x'|MERGE",
        "CALL my_proc()|CALL",
        "HANDLER users OPEN|HANDLER",
        "LOCK TABLES users WRITE|LOCK",
        "UNLOCK TABLES|UNLOCK",
        "FLUSH TABLES|FLUSH",
        "RESET QUERY CACHE|RESET",
        "SHUTDOWN|SHUTDOWN",
        "KILL 12345|KILL",
        "LOAD DATA INFILE '/tmp/x' INTO TABLE users|LOAD",
    })
    void shouldRejectWhenFirstKeywordIsWriteOperation(String sql, String keyword) {
        SqlGuard.Result result = guard.validate(sql);

        assertThat(result.isAllowed()).isFalse();
        assertThat(result.getReason()).contains(keyword);
    }

    // ---- Reject: blacklist keywords (even when first keyword is SELECT) ----

    @Test
    void shouldRejectWhenSqlContainsIntoOutfile() {
        SqlGuard.Result result = guard.validate("SELECT * FROM users INTO OUTFILE '/tmp/x'");

        assertThat(result.isAllowed()).isFalse();
        assertThat(result.getReason()).contains("INTO OUTFILE");
    }

    @Test
    void shouldRejectWhenSqlContainsIntoDumpfile() {
        SqlGuard.Result result = guard.validate("SELECT * FROM users INTO DUMPFILE '/tmp/x'");

        assertThat(result.isAllowed()).isFalse();
        assertThat(result.getReason()).contains("INTO DUMPFILE");
    }

    @Test
    void shouldRejectWhenSqlContainsLoadFile() {
        SqlGuard.Result result = guard.validate("SELECT LOAD_FILE('/etc/passwd')");

        assertThat(result.isAllowed()).isFalse();
        assertThat(result.getReason()).contains("LOAD_FILE");
    }

    @Test
    void shouldRejectWhenSqlContainsSleep() {
        SqlGuard.Result result = guard.validate("SELECT SLEEP(60)");

        assertThat(result.isAllowed()).isFalse();
        assertThat(result.getReason()).contains("SLEEP");
    }

    @Test
    void shouldRejectWhenSqlContainsBenchmark() {
        SqlGuard.Result result = guard.validate("SELECT BENCHMARK(1000000, MD5('x'))");

        assertThat(result.isAllowed()).isFalse();
        assertThat(result.getReason()).contains("BENCHMARK");
    }

    // ---- Reject: multi-statement ----

    @Test
    void shouldRejectWhenSqlContainsMultipleStatements() {
        SqlGuard.Result result = guard.validate("SELECT 1; DROP TABLE users; --");

        assertThat(result.isAllowed()).isFalse();
        assertThat(result.getReason()).contains("多语句");
    }

    @Test
    void shouldRejectWhenSqlContainsSemicolonAfterTrailingTrim() {
        SqlGuard.Result result = guard.validate("SELECT 1; SELECT 2");

        assertThat(result.isAllowed()).isFalse();
        assertThat(result.getReason()).contains("多语句");
    }

    // ---- Pass: basic SELECT ----

    @Test
    void shouldPassAndAppendLimitWhenSelectHasNoLimit() {
        SqlGuard.Result result = guard.validate("SELECT * FROM users");

        assertThat(result.isAllowed()).isTrue();
        assertThat(result.getSql()).contains("LIMIT 1000");
    }

    @Test
    void shouldPassAndNotModifyLimitWhenLimitWithinMax() {
        SqlGuard.Result result = guard.validate("SELECT * FROM users LIMIT 100");

        assertThat(result.isAllowed()).isTrue();
        assertThat(result.getSql()).contains("LIMIT 100");
        assertThat(result.getSql()).doesNotContain("LIMIT 1000");
    }

    @Test
    void shouldRewriteLimitWhenExceedsMax() {
        SqlGuard.Result result = guard.validate("SELECT * FROM users LIMIT 5000");

        assertThat(result.isAllowed()).isTrue();
        assertThat(result.getSql()).contains("LIMIT 1000");
        assertThat(result.getSql()).doesNotContain("LIMIT 5000");
    }

    // ---- Pass: CTE (WITH) ----

    @Test
    void shouldPassWhenFirstKeywordIsWith() {
        SqlGuard.Result result = guard.validate("WITH agg AS (SELECT 1) SELECT * FROM agg");

        assertThat(result.isAllowed()).isTrue();
        assertThat(result.getSql()).contains("LIMIT 1000");
    }

    // ---- Pass: SHOW / DESCRIBE / EXPLAIN / DESC ----

    @ParameterizedTest
    @ValueSource(strings = {
        "SHOW TABLES",
        "SHOW DATABASES",
        "DESCRIBE users",
        "DESC users",
        "EXPLAIN SELECT * FROM users",
    })
    void shouldPassWhenFirstKeywordIsShowOrDescribeOrExplainOrDesc(String sql) {
        SqlGuard.Result result = guard.validate(sql);

        assertThat(result.isAllowed()).isTrue();
    }

    @Test
    void shouldNotAppendLimitForDescribe() {
        SqlGuard.Result result = guard.validate("DESCRIBE users");

        assertThat(result.isAllowed()).isTrue();
        assertThat(result.getSql()).doesNotContain("LIMIT");
    }

    @Test
    void shouldNotAppendLimitForDesc() {
        SqlGuard.Result result = guard.validate("DESC users");

        assertThat(result.isAllowed()).isTrue();
        assertThat(result.getSql()).doesNotContain("LIMIT");
    }

    @Test
    void shouldNotAppendLimitForShowTables() {
        SqlGuard.Result result = guard.validate("SHOW TABLES");

        assertThat(result.isAllowed()).isTrue();
        assertThat(result.getSql()).doesNotContain("LIMIT");
    }

    @Test
    void shouldNotAppendLimitForShowColumns() {
        SqlGuard.Result result = guard.validate("SHOW COLUMNS FROM drp_replenishment_strategy_parameters");

        assertThat(result.isAllowed()).isTrue();
        assertThat(result.getSql()).doesNotContain("LIMIT");
    }

    @Test
    void shouldNotAppendLimitForShowDatabases() {
        SqlGuard.Result result = guard.validate("SHOW DATABASES");

        assertThat(result.isAllowed()).isTrue();
        assertThat(result.getSql()).doesNotContain("LIMIT");
    }

    @Test
    void shouldAppendLimitForExplain() {
        SqlGuard.Result result = guard.validate("EXPLAIN SELECT * FROM users");

        assertThat(result.isAllowed()).isTrue();
        // EXPLAIN wraps a SELECT — LIMIT applies to the inner SELECT
        assertThat(result.getSql()).contains("LIMIT 1000");
    }

    // ---- Pass: trailing semicolon removed ----

    @Test
    void shouldPassWhenTrailingSemicolonIsRemoved() {
        SqlGuard.Result result = guard.validate("SELECT 1;");

        assertThat(result.isAllowed()).isTrue();
        assertThat(result.getSql()).doesNotEndWith(";");
    }

    // ---- Pass: comments stripped ----

    @Test
    void shouldPassAndStripLineComment() {
        SqlGuard.Result result = guard.validate("SELECT 1 -- comment\n");

        assertThat(result.isAllowed()).isTrue();
    }

    // ---- Edge cases ----

    @Test
    void shouldRejectWhenSqlIsBlank() {
        SqlGuard.Result result = guard.validate("   ");

        assertThat(result.isAllowed()).isFalse();
    }

    @Test
    void shouldRejectWhenSqlIsNull() {
        SqlGuard.Result result = guard.validate(null);

        assertThat(result.isAllowed()).isFalse();
    }

    @Test
    void shouldRejectWhenSqlIsEmpty() {
        SqlGuard.Result result = guard.validate("");

        assertThat(result.isAllowed()).isFalse();
    }

    @Test
    void shouldRejectWhenFirstKeywordIsSelectButContainsInsertInSubquery() {
        SqlGuard.Result result = guard.validate("SELECT * FROM (INSERT INTO users VALUES(1))");

        assertThat(result.isAllowed()).isFalse();
        assertThat(result.getReason()).contains("INSERT");
    }

    // ---- Custom maxResultRows ----

    @Test
    void shouldUseCustomMaxRowsWhenLimitInjected() {
        SqlGuard customGuard = new SqlGuard(500);

        SqlGuard.Result result = customGuard.validate("SELECT * FROM users");

        assertThat(result.isAllowed()).isTrue();
        assertThat(result.getSql()).contains("LIMIT 500");
    }

    @Test
    void shouldRewriteToCustomMaxWhenLimitExceedsCustomMax() {
        SqlGuard customGuard = new SqlGuard(500);

        SqlGuard.Result result = customGuard.validate("SELECT * FROM users LIMIT 800");

        assertThat(result.isAllowed()).isTrue();
        assertThat(result.getSql()).contains("LIMIT 500");
        assertThat(result.getSql()).doesNotContain("LIMIT 800");
    }

    // ---- Case insensitivity ----

    @Test
    void shouldRejectWhenDeleteKeywordIsLowercase() {
        SqlGuard.Result result = guard.validate("delete from users where id=1");

        assertThat(result.isAllowed()).isFalse();
        assertThat(result.getReason()).contains("DELETE");
    }

    @Test
    void shouldPassWhenSelectKeywordIsLowercase() {
        SqlGuard.Result result = guard.validate("select * from users");

        assertThat(result.isAllowed()).isTrue();
        assertThat(result.getSql()).contains("LIMIT 1000");
    }
}
