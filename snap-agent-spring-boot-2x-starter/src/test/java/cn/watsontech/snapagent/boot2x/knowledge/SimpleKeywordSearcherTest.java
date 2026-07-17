package cn.watsontech.snapagent.boot2x.knowledge;

import cn.watsontech.snapagent.core.knowledge.KnowledgeFragment;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class SimpleKeywordSearcherTest {

    private final SimpleKeywordSearcher searcher = new SimpleKeywordSearcher();

    @Test
    void score_englishKeywordMatch_returnsPositiveScore() {
        KnowledgeFragment fragment = new KnowledgeFragment(
                "Database Diagnostics", "database connection pool issues", "src", null);
        double score = searcher.score("database connection pool", fragment);
        assertThat(score).isGreaterThan(0.5);
    }

    @Test
    void score_noOverlap_returnsZero() {
        KnowledgeFragment fragment = new KnowledgeFragment(
                "Database", "database content here", "src", null);
        double score = searcher.score("redis cache", fragment);
        assertThat(score).isLessThan(0.01);
    }

    @Test
    void score_emptyQuery_returnsZero() {
        KnowledgeFragment fragment = new KnowledgeFragment("Title", "content", "src", null);
        assertThat(searcher.score("", fragment)).isZero();
        assertThat(searcher.score(null, fragment)).isZero();
    }

    @Test
    void score_nullFragment_returnsZero() {
        assertThat(searcher.score("query", null)).isZero();
    }

    @Test
    void score_titleMatchWeightedDouble() {
        // Title has the keywords, content doesn't
        KnowledgeFragment titleOnly = new KnowledgeFragment(
                "Database Diagnostics", "completely unrelated text here", "src", null);
        // Content has the keywords, title doesn't
        KnowledgeFragment contentOnly = new KnowledgeFragment(
                "Other Title", "database diagnostics content here", "src2", null);

        double titleScore = searcher.score("database diagnostics", titleOnly);
        double contentScore = searcher.score("database diagnostics", contentOnly);

        // Title match should score higher due to 2x weighting
        assertThat(titleScore).isGreaterThan(contentScore);
        // Title-only: (2*2 + 0) / (2*2) = 4/4 = 1.0
        assertThat(titleScore).isCloseTo(1.0, within(0.001));
        // Content-only: (0*2 + 2) / (2*2) = 2/4 = 0.5
        assertThat(contentScore).isCloseTo(0.5, within(0.001));
    }

    @Test
    void score_chineseBigramMatch_returnsPositiveScore() {
        KnowledgeFragment fragment = new KnowledgeFragment(
                "补货策略", "补货策略生成规则和参数配置", "src", null);
        double score = searcher.score("补货策略", fragment);
        // "补货策略" bigrams: ["补货", "货策", "策略"]
        // fragment title "补货策略" bigrams: ["补货", "货策", "策略"]
        // fragment content contains "补货策略" → ["补货", "货策", "策略"]
        // All 3 query tokens hit both title and content
        assertThat(score).isGreaterThan(0.5);
    }

    @Test
    void score_partialChineseMatch_returnsPartialScore() {
        KnowledgeFragment fragment = new KnowledgeFragment(
                "数据库诊断", "只读查询和连接池管理", "src", null);
        double score = searcher.score("数据库连接", fragment);
        // "数据库连接" bigrams: ["数据库"... no wait, 3 chars → ["数据", "据库", "库连"]
        // fragment title "数据库诊断" bigrams: ["数据", "据库", "库诊"]
        // fragment content "只读查询和连接池管理" bigrams: ["只读", "读查", "查询", "询和", "和连", "连接", "接池", "池管", "管理"]
        // query "数据库连接" bigrams: ["数据", "据库", "库连"]
        // "数据" hits title, "据库" hits title, "库连" hits content (from 连接...no, 库连 != 连接)
        // Actually "库连" won't match. "数据" and "据库" hit title (2 title hits).
        // content: none of ["数据", "据库", "库连"] match content bigrams
        // score = (2*2 + 0) / (3*2) = 4/6 = 0.667
        assertThat(score).isGreaterThan(0.3);
    }

    @Test
    void score_allQueryTokensMatch_returnsOne() {
        KnowledgeFragment fragment = new KnowledgeFragment(
                "redis cache", "redis cache tool", "src", null);
        double score = searcher.score("redis cache", fragment);
        assertThat(score).isCloseTo(1.0, within(0.001));
    }

    @Test
    void score_isClampedToZeroToOne() {
        // A fragment containing all query words in both title and content
        KnowledgeFragment fragment = new KnowledgeFragment(
                "java spring maven", "java spring maven tool", "src", null);
        double score = searcher.score("java spring maven", fragment);
        assertThat(score).isLessThanOrEqualTo(1.0);
    }

    @Test
    void score_singleTokenQuery_returnsZero() {
        // A single-token query is too short to match (< 2 tokens → 0)
        KnowledgeFragment fragment = new KnowledgeFragment(
                "Database Tools", "database tools here", "src", null);
        assertThat(searcher.score("database", fragment)).isZero();
        // A single CJK character also produces only 1 token → 0
        KnowledgeFragment cnFragment = new KnowledgeFragment(
                "补货策略", "补货策略内容", "src", null);
        assertThat(searcher.score("补", cnFragment)).isZero();
    }

    @Test
    void score_caseInsensitive_matchingUppercaseQuery() {
        KnowledgeFragment fragment = new KnowledgeFragment(
                "Database Diagnostics", "database connection pool", "src", null);
        // Uppercase query should match lowercase fragment tokens
        double score = searcher.score("DATABASE CONNECTION", fragment);
        assertThat(score).isGreaterThan(0.5);
    }

    @Test
    void tokenize_english_lowercasesAndSplitsOnPunctuation() {
        List<String> tokens = searcher.tokenize("Hello, World! Foo-Bar");
        assertThat(tokens).contains("hello", "world", "foo", "bar");
    }

    @Test
    void tokenize_chinese_generatesBigrams() {
        List<String> tokens = searcher.tokenize("补货策略");
        assertThat(tokens).contains("补货", "货策", "策略");
    }

    @Test
    void tokenize_dropsShortLatinTokens() {
        // Tokens < 2 chars are dropped (crude stopword filter for "a", "I", etc.)
        List<String> tokens = searcher.tokenize("a I database");
        assertThat(tokens).contains("database");
        assertThat(tokens).doesNotContain("a");
        assertThat(tokens).doesNotContain("I".toLowerCase()); // "i" is 1 char, dropped
    }

    @Test
    void tokenize_emptyString_returnsEmptyList() {
        assertThat(searcher.tokenize("")).isEmpty();
        assertThat(searcher.tokenize(null)).isEmpty();
    }
}
