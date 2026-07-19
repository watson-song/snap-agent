package cn.watsontech.snapagent.core.knowledge;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link KnowledgeBase}.
 *
 * <p>Uses simple mock {@link KnowledgeSource} and {@link KnowledgeSearcher}
 * implementations that return fixed fragments and scores for deterministic
 * test behavior.</p>
 */
class KnowledgeBaseTest {

    private KnowledgeFragment fragment(String title, String content) {
        return new KnowledgeFragment(title, content, "test-source", null);
    }

    /** A KnowledgeSource that returns a fixed list of fragments. */
    private KnowledgeSource sourceWith(List<KnowledgeFragment> fragments) {
        return new KnowledgeSource() {
            @Override
            public List<KnowledgeFragment> load() {
                return fragments;
            }

            @Override
            public void reload() {
                // no-op for test
            }

            @Override
            public String type() {
                return "mock";
            }
        };
    }

    /**
     * A KnowledgeSearcher that scores based on whether the query string is
     * contained in the fragment content.
     */
    private KnowledgeSearcher contentContainsSearcher() {
        return (query, fragment) -> {
            if (fragment.getContent() != null && fragment.getContent().contains(query)) {
                return 1.0;
            }
            return 0.0;
        };
    }

    /** A KnowledgeSearcher that returns a fixed score for all fragments. */
    private KnowledgeSearcher fixedScoreSearcher(double score) {
        return (query, fragment) -> score;
    }

    @Test
    void shouldReturnMatchingFragmentsOnSearch() {
        KnowledgeFragment f1 = fragment("数据库诊断", "连接池打满检查 max-pool-size");
        KnowledgeFragment f2 = fragment("日志分析", "OOM 错误分析");
        KnowledgeBase kb = new KnowledgeBase(
                Collections.singletonList(sourceWith(Arrays.asList(f1, f2))),
                contentContainsSearcher());

        List<KnowledgeFragment> result = kb.search("连接池", 5, 0.5);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTitle()).isEqualTo("数据库诊断");
    }

    @Test
    void shouldReturnEmptyWhenNoMatches() {
        KnowledgeFragment f1 = fragment("数据库诊断", "连接池打满检查");
        KnowledgeBase kb = new KnowledgeBase(
                Collections.singletonList(sourceWith(Collections.singletonList(f1))),
                contentContainsSearcher());

        List<KnowledgeFragment> result = kb.search("Redis 缓存", 5, 0.5);

        assertThat(result).isEmpty();
    }

    @Test
    void shouldLimitResultsToTopK() {
        KnowledgeFragment f1 = fragment("片段1", "匹配关键词");
        KnowledgeFragment f2 = fragment("片段2", "匹配关键词");
        KnowledgeFragment f3 = fragment("片段3", "匹配关键词");
        KnowledgeBase kb = new KnowledgeBase(
                Collections.singletonList(sourceWith(Arrays.asList(f1, f2, f3))),
                fixedScoreSearcher(1.0));

        List<KnowledgeFragment> result = kb.search("匹配", 2, 0.5);

        assertThat(result).hasSize(2);
    }

    @Test
    void shouldFilterByMinScore() {
        KnowledgeFragment f1 = fragment("高相关", "内容1");
        KnowledgeFragment f2 = fragment("低相关", "内容2");
        KnowledgeBase kb = new KnowledgeBase(
                Collections.singletonList(sourceWith(Arrays.asList(f1, f2))),
                contentContainsSearcher());

        // f1 matches (score 1.0), f2 does not (score 0.0); minScore 0.5 filters out f2
        List<KnowledgeFragment> result = kb.search("内容1", 5, 0.5);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTitle()).isEqualTo("高相关");
    }

    @Test
    void shouldReturnEmptyForEmptyQuery() {
        KnowledgeFragment f1 = fragment("片段1", "内容1");
        KnowledgeBase kb = new KnowledgeBase(
                Collections.singletonList(sourceWith(Collections.singletonList(f1))),
                fixedScoreSearcher(1.0));

        assertThat(kb.search("", 5, 0.0)).isEmpty();
        assertThat(kb.search(null, 5, 0.0)).isEmpty();
    }

    @Test
    void shouldReturnEmptyWhenNoSources() {
        KnowledgeBase kb = new KnowledgeBase(
                Collections.<KnowledgeSource>emptyList(),
                fixedScoreSearcher(1.0));

        List<KnowledgeFragment> result = kb.search("anything", 5, 0.0);

        assertThat(result).isEmpty();
        assertThat(kb.size()).isEqualTo(0);
    }

    @Test
    void shouldReloadFragmentsFromSources() {
        AtomicInteger loadCount = new AtomicInteger(0);
        KnowledgeSource dynamicSource = new KnowledgeSource() {
            @Override
            public List<KnowledgeFragment> load() {
                loadCount.incrementAndGet();
                if (loadCount.get() == 1) {
                    return Collections.singletonList(fragment("初始片段", "初始内容"));
                }
                return Arrays.asList(
                        fragment("初始片段", "初始内容"),
                        fragment("新增片段", "新增内容"));
            }

            @Override
            public void reload() {
                // no-op — reload() on KnowledgeBase re-calls load()
            }

            @Override
            public String type() {
                return "mock";
            }
        };

        KnowledgeBase kb = new KnowledgeBase(
                Collections.singletonList(dynamicSource),
                fixedScoreSearcher(1.0));

        assertThat(kb.size()).isEqualTo(1);

        kb.reload();

        assertThat(kb.size()).isEqualTo(2);
        assertThat(kb.search("内容", 5, 0.5)).hasSize(2);
    }

    @Test
    void shouldReturnTotalFragmentCount() {
        KnowledgeFragment f1 = fragment("片段1", "内容1");
        KnowledgeFragment f2 = fragment("片段2", "内容2");
        KnowledgeFragment f3 = fragment("片段3", "内容3");

        KnowledgeBase kb = new KnowledgeBase(
                Arrays.asList(
                        sourceWith(Collections.singletonList(f1)),
                        sourceWith(Arrays.asList(f2, f3))),
                fixedScoreSearcher(1.0));

        assertThat(kb.size()).isEqualTo(3);
    }
}
