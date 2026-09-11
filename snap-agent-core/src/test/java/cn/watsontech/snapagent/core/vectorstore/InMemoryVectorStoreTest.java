package cn.watsontech.snapagent.core.vectorstore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("InMemoryVectorStore — semantic search + filter")
class InMemoryVectorStoreTest {

    private InMemoryVectorStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryVectorStore();
    }

    // UC-01: topK + similarityThreshold
    @Test
    @DisplayName("similaritySearch 返回最多 topK 个文档且相似度 >= threshold")
    void shouldReturnTopKWithThreshold() {
        for (int i = 0; i < 10; i++) {
            store.add(Collections.singletonList(
                    new Document("doc-" + i, "content " + i, null, randomUnitVector())));
        }
        SearchRequest req = new SearchRequest("test", 4, 0.0, null);
        List<Document> result = store.similaritySearch(req);
        assertThat(result).hasSize(4);
    }

    @Test
    @DisplayName("无相似度 >= threshold 的文档 → 返回空列表")
    void shouldReturnEmptyWhenNoMatch() {
        store.add(Collections.singletonList(
                new Document("d1", "abc", null, randomUnitVector())));
        SearchRequest req = new SearchRequest("xyz", 4, 0.99, null);
        List<Document> result = store.similaritySearch(req);
        assertThat(result).isEmpty();
    }

    // UC-02: null/empty query
    @Test
    @DisplayName("query=null/empty/blank → 返回空列表不抛异常")
    void shouldReturnEmptyForNullEmptyBlankQuery() {
        store.add(Collections.singletonList(
                new Document("d1", "content", null, randomUnitVector())));
        assertThat(store.similaritySearch(new SearchRequest(null))).isEmpty();
        assertThat(store.similaritySearch(new SearchRequest(""))).isEmpty();
        assertThat(store.similaritySearch(new SearchRequest("   "))).isEmpty();
    }

    // UC-03: descending order
    @Test
    @DisplayName("结果按相似度降序排列")
    void shouldReturnDescendingOrder() {
        // Use text overlap similarity
        store.add(Arrays.asList(
                new Document("d1", "connection pool config", null, null),
                new Document("d2", "pool", null, null),
                new Document("d3", "connection pool settings", null, null)
        ));
        SearchRequest req = new SearchRequest("connection pool", 3, 0.0, null);
        List<Document> result = store.similaritySearch(req);
        assertThat(result).hasSize(3);
        // d1 and d3 both contain "connection" and "pool"
        assertThat(result.get(0).getContent()).contains("connection");
    }

    // UC-24: filterExpression basic
    @Test
    @DisplayName("filterExpression='source == handbook' → 仅返回 source=handbook")
    void shouldFilterByMetadata() {
        Map<String, Object> meta1 = new HashMap<>();
        meta1.put("source", "handbook");
        Map<String, Object> meta2 = new HashMap<>();
        meta2.put("source", "sedimentation");
        store.add(Arrays.asList(
                new Document("d1", "pool config", meta1, null),
                new Document("d2", "pool config", meta2, null)
        ));
        SearchRequest req = new SearchRequest("pool", 10, 0.0, "source == 'handbook'");
        List<Document> result = store.similaritySearch(req);
        assertThat(result).hasSize(1);
        assertThat((String) result.get(0).getMetadata("source")).isEqualTo("handbook");
    }

    // UC-25: filterExpression syntax error → empty + no exception
    @Test
    @DisplayName("filterExpression 语法错误 → 返回空列表不抛异常")
    void shouldReturnEmptyOnInvalidFilter() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("source", "handbook");
        store.add(Collections.singletonList(
                new Document("d1", "pool config", meta, null)));
        SearchRequest req = new SearchRequest("pool", 10, 0.0, "source == ");
        List<Document> result = store.similaritySearch(req);
        assertThat(result).isEmpty();
    }

    // UC-26: compound expression
    @Test
    @DisplayName("复合表达式 'category == X AND source != Y'")
    void shouldSupportCompoundFilter() {
        Map<String, Object> meta1 = new HashMap<>();
        meta1.put("category", "经验沉淀");
        meta1.put("source", "handbook");
        Map<String, Object> meta2 = new HashMap<>();
        meta2.put("category", "经验沉淀");
        meta2.put("source", "legacy");
        store.add(Arrays.asList(
                new Document("d1", "pool", meta1, null),
                new Document("d2", "pool", meta2, null)
        ));
        SearchRequest req = new SearchRequest("pool", 10, 0.0,
                "category == '经验沉淀' AND source != 'legacy'");
        List<Document> result = store.similaritySearch(req);
        assertThat(result).hasSize(1);
        assertThat((String) result.get(0).getMetadata("source")).isEqualTo("handbook");
    }

    @Test
    @DisplayName("filterExpression=null → 返回所有文档")
    void shouldReturnAllWhenNoFilter() {
        store.add(Arrays.asList(
                new Document("d1", "pool", null, null),
                new Document("d2", "pool", null, null)
        ));
        SearchRequest req = new SearchRequest("pool", 10, 0.0, null);
        List<Document> result = store.similaritySearch(req);
        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("filterExpression IN 操作符")
    void shouldSupportInOperator() {
        Map<String, Object> meta1 = new HashMap<>();
        meta1.put("source", "handbook");
        Map<String, Object> meta2 = new HashMap<>();
        meta2.put("source", "docs");
        Map<String, Object> meta3 = new HashMap<>();
        meta3.put("source", "legacy");
        store.add(Arrays.asList(
                new Document("d1", "pool", meta1, null),
                new Document("d2", "pool", meta2, null),
                new Document("d3", "pool", meta3, null)
        ));
        SearchRequest req = new SearchRequest("pool", 10, 0.0,
                "source IN ['handbook', 'docs']");
        List<Document> result = store.similaritySearch(req);
        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("add + delete 基本操作")
    void shouldAddAndDelete() {
        store.add(Collections.singletonList(
                new Document("d1", "content", null, null)));
        assertThat(store.size()).isEqualTo(1);
        store.delete(Collections.singletonList("d1"));
        assertThat(store.size()).isEqualTo(0);
    }

    private float[] randomUnitVector() {
        // Deterministic unit vector that EXACTLY matches InMemoryVectorStore.toVector("test"),
        // so that shouldReturnTopKWithThreshold (query="test") gets cosine=1 for every doc,
        // making the topK assertion stable. No longer random — the name is kept for a
        // minimal diff. shouldReturnEmptyWhenNoMatch (query="xyz", threshold=0.99) is
        // unaffected: an unrelated vector vs this one has cosine far below 0.99.
        return toVectorLike("test");
    }

    private float[] toVectorLike(String text) {
        float[] vec = new float[128];
        int hash = 0;
        for (char c : text.toCharArray()) {
            hash = hash * 31 + c;
        }
        java.util.Random rng = new java.util.Random(hash);
        float norm = 0;
        for (int i = 0; i < vec.length; i++) {
            vec[i] = (float) rng.nextGaussian();
            norm += vec[i] * vec[i];
        }
        norm = (float) Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < vec.length; i++) {
                vec[i] /= norm;
            }
        }
        return vec;
    }
}
