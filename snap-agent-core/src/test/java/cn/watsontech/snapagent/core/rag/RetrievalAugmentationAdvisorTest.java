package cn.watsontech.snapagent.core.rag;

import cn.watsontech.snapagent.core.graph.GraphState;
import cn.watsontech.snapagent.core.vectorstore.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RetrievalAugmentationAdvisor — RAG injection")
class RetrievalAugmentationAdvisorTest {

    // UC-15: beforeNode transform→retrieve→augment
    @Test
    @DisplayName("beforeNode 依次调用 transform→retrieve→augment → state['rag.context']")
    void shouldTransformRetrieveAugment() throws Exception {
        QueryTransformer transformer = (q, ctx) -> q + " (expanded)";
        DocumentRetriever retriever = (q, topK) -> Arrays.asList(
                new Document("d1", "连接池配置: max=20", null, null));
        QueryAugmenter augmenter = new DefaultQueryAugmenter(false);

        RetrievalAugmentationAdvisor advisor = new RetrievalAugmentationAdvisor(
                transformer, retriever, augmenter);

        GraphState state = GraphState.empty("t1").with("user.query", "如何配置连接池");
        GraphState result = advisor.beforeNode("agent", state, null);

        String ragContext = result.get("rag.context");
        assertThat(ragContext).contains("如何配置连接池");
        assertThat(ragContext).contains("连接池配置: max=20");
    }

    // UC-16: no user.query → empty rag.context
    @Test
    @DisplayName("state 无 user.query → rag.context 为空字符串不抛异常")
    void shouldHandleMissingUserQuery() throws Exception {
        QueryTransformer transformer = (q, ctx) -> q;
        DocumentRetriever retriever = (q, topK) -> Collections.emptyList();
        QueryAugmenter augmenter = new DefaultQueryAugmenter(false);

        RetrievalAugmentationAdvisor advisor = new RetrievalAugmentationAdvisor(
                transformer, retriever, augmenter);

        GraphState state = GraphState.empty("t1");
        GraphState result = advisor.beforeNode("agent", state, null);

        assertThat((String) result.get("rag.context")).isEqualTo("");
    }

    // UC-17: exception isolation
    @Test
    @DisplayName("DocumentRetriever 抛异常 → rag.context 为空不中断")
    void shouldIsolateExceptions() throws Exception {
        QueryTransformer transformer = (q, ctx) -> q;
        DocumentRetriever retriever = (q, topK) -> {
            throw new RuntimeException("VectorStore unavailable");
        };
        QueryAugmenter augmenter = new DefaultQueryAugmenter(false);

        RetrievalAugmentationAdvisor advisor = new RetrievalAugmentationAdvisor(
                transformer, retriever, augmenter);

        GraphState state = GraphState.empty("t1").with("user.query", "test");
        GraphState result = advisor.beforeNode("agent", state, null);

        assertThat((String) result.get("rag.context")).isEqualTo("");
    }

    // AC: empty docs → "无相关知识" instruction
    @Test
    @DisplayName("检索返回空文档 → rag.context 含 '无相关知识' 指令")
    void shouldInjectNoKnowledgeInstruction() throws Exception {
        QueryTransformer transformer = (q, ctx) -> q;
        DocumentRetriever retriever = (q, topK) -> Collections.emptyList();
        QueryAugmenter augmenter = new DefaultQueryAugmenter(false);

        RetrievalAugmentationAdvisor advisor = new RetrievalAugmentationAdvisor(
                transformer, retriever, augmenter);

        GraphState state = GraphState.empty("t1").with("user.query", "test");
        GraphState result = advisor.beforeNode("agent", state, null);

        assertThat((String) result.get("rag.context")).contains("无相关知识");
    }

    // AC: afterNode noop
    @Test
    @DisplayName("afterNode → 不修改 state")
    void shouldNoopAfterNode() throws Exception {
        RetrievalAugmentationAdvisor advisor = new RetrievalAugmentationAdvisor(
                (q, ctx) -> q, (q, k) -> Collections.emptyList(), new DefaultQueryAugmenter());

        GraphState state = GraphState.empty("t1").with("rag.context", "existing");
        GraphState result = advisor.afterNode("agent", state, null);
        assertThat(result).isEqualTo(state);
    }

    @Test
    @DisplayName("order=200")
    void shouldReturnOrder200() {
        RetrievalAugmentationAdvisor advisor = new RetrievalAugmentationAdvisor(
                null, null, null);
        assertThat(advisor.getOrder()).isEqualTo(200);
    }

    @Test
    @DisplayName("getName='retrieval-augmentation'")
    void shouldReturnName() {
        RetrievalAugmentationAdvisor advisor = new RetrievalAugmentationAdvisor(
                null, null, null);
        assertThat(advisor.getName()).isEqualTo("retrieval-augmentation");
    }
}
