package cn.watsontech.snapagent.core.rag;

import cn.watsontech.snapagent.core.vectorstore.Document;
import cn.watsontech.snapagent.core.vectorstore.InMemoryVectorStore;
import cn.watsontech.snapagent.core.vectorstore.SearchRequest;
import cn.watsontech.snapagent.core.vectorstore.VectorStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Modular RAG — QueryTransformer + DocumentRetriever + QueryAugmenter")
class ModularRagTest {

    // UC-11: QueryTransformer rewrite
    @Test
    @DisplayName("QueryTransformer.transform → 重写 query")
    void shouldTransformQuery() {
        QueryTransformer transformer = (query, ctx) -> "扩展后: " + query;
        String result = transformer.transform("原 query", null);
        assertThat(result).isEqualTo("扩展后: 原 query");
    }

    // UC-12: DocumentRetriever retrieve
    @Test
    @DisplayName("DocumentRetriever.retrieve → 返回 topK 文档")
    void shouldRetrieveDocuments() {
        VectorStore store = new InMemoryVectorStore();
        store.add(Arrays.asList(
                new Document("d1", "pool config", null, null),
                new Document("d2", "pool settings", null, null),
                new Document("d3", "timeout", null, null)
        ));
        DocumentRetriever retriever = (query, topK) ->
                store.similaritySearch(new SearchRequest(query, topK, 0.0, null));
        List<Document> docs = retriever.retrieve("pool", 2);
        assertThat(docs).hasSize(2);
    }

    // UC-13: QueryAugmenter inject
    @Test
    @DisplayName("augment(query, docs) → 含 originalQuery 和 docs 内容")
    void shouldAugmentWithDocs() {
        QueryAugmenter augmenter = new DefaultQueryAugmenter(false);
        List<Document> docs = Arrays.asList(
                new Document("d1", "连接池最大20", null, null));
        String result = augmenter.augment("如何配置连接池?", docs);
        assertThat(result).contains("如何配置连接池?");
        assertThat(result).contains("连接池最大20");
        assertThat(result).contains("相关知识");
    }

    // UC-14: empty docs → keep original query (allowEmptyContext=true)
    @Test
    @DisplayName("augment(query, []) + allowEmptyContext=true → 保持原 query")
    void shouldKeepOriginalWhenEmptyAndAllowed() {
        QueryAugmenter augmenter = new DefaultQueryAugmenter(true);
        String result = augmenter.augment("原 query", Collections.emptyList());
        assertThat(result).isEqualTo("原 query");
    }

    // UC-18: allowEmptyContext=false → "无相关知识" instruction
    @Test
    @DisplayName("allowEmptyContext=false + 空文档 → '无相关知识' 指令")
    void shouldReturnNoKnowledgeInstruction() {
        QueryAugmenter augmenter = new DefaultQueryAugmenter(false);
        String result = augmenter.augment("原 query", Collections.emptyList());
        assertThat(result).contains("无相关知识");
    }

    // UC-19: allowEmptyContext=true → empty string
    @Test
    @DisplayName("allowEmptyContext=true + 空文档 → 原query")
    void shouldReturnOriginalWhenEmptyAndAllowed() {
        QueryAugmenter augmenter = new DefaultQueryAugmenter(true);
        String result = augmenter.augment("原 query", Collections.emptyList());
        assertThat(result).doesNotContain("无相关知识");
        assertThat(result).isEqualTo("原 query");
    }

    // UC-18: default allowEmptyContext=false
    @Test
    @DisplayName("默认构造 → allowEmptyContext=false")
    void shouldDefaultToFalse() {
        DefaultQueryAugmenter augmenter = new DefaultQueryAugmenter();
        assertThat(augmenter.isAllowEmptyContext()).isFalse();
    }

    // UC-14: three SPIs independently replaceable
    @Test
    @DisplayName("三段式 SPI 可独立替换")
    void shouldAllowIndependentReplacement() {
        QueryTransformer t1 = (q, ctx) -> q + " (expanded)";
        QueryTransformer t2 = (q, ctx) -> q.toUpperCase();
        assertThat(t1.transform("hello", null)).isEqualTo("hello (expanded)");
        assertThat(t2.transform("hello", null)).isEqualTo("HELLO");

        QueryAugmenter a1 = new DefaultQueryAugmenter(false);
        QueryAugmenter a2 = new DefaultQueryAugmenter(true);
        assertThat(a1.augment("q", Collections.emptyList())).contains("无相关知识");
        assertThat(a2.augment("q", Collections.emptyList())).isEqualTo("q");
    }
}
