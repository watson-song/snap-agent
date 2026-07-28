package cn.watsontech.snapagent.core.rag;

import cn.watsontech.snapagent.core.graph.GraphState;
import cn.watsontech.snapagent.core.graph.StateKeys;
import cn.watsontech.snapagent.core.graph.advisor.Advisor;
import cn.watsontech.snapagent.core.graph.hitl.InterruptException;
import cn.watsontech.snapagent.core.vectorstore.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;

/**
 * Advisor (order=200) that performs modular RAG before the agent node.
 *
 * <p>beforeNode("agent"): transforms the user query, retrieves documents,
 * augments the query with context, and injects into state["rag.context"].</p>
 *
 * <p>afterNode: noop (RAG only injects before the agent runs).</p>
 *
 * <p>Exception isolation: if any stage fails, logs WARN and sets
 * state["rag.context"] to empty string — graph execution continues.</p>
 */
public class RetrievalAugmentationAdvisor implements Advisor {

    private static final Logger log = LoggerFactory.getLogger(RetrievalAugmentationAdvisor.class);

    private final QueryTransformer queryTransformer;
    private final DocumentRetriever documentRetriever;
    private final QueryAugmenter queryAugmenter;
    private final int topK;

    public RetrievalAugmentationAdvisor(QueryTransformer queryTransformer,
                                        DocumentRetriever documentRetriever,
                                        QueryAugmenter queryAugmenter) {
        this(queryTransformer, documentRetriever, queryAugmenter, 4);
    }

    public RetrievalAugmentationAdvisor(QueryTransformer queryTransformer,
                                        DocumentRetriever documentRetriever,
                                        QueryAugmenter queryAugmenter,
                                        int topK) {
        this.queryTransformer = queryTransformer;
        this.documentRetriever = documentRetriever;
        this.queryAugmenter = queryAugmenter;
        this.topK = topK;
    }

    @Override
    public int getOrder() { return 200; }

    @Override
    public String getName() { return "retrieval-augmentation"; }

    @Override
    public GraphState beforeNode(String nodeName, GraphState state, Object ctx) throws InterruptException {
        String userQuery = state.get(StateKeys.USER_QUERY);
        if (userQuery == null || userQuery.trim().isEmpty()) {
            return state.with(StateKeys.RAG_CONTEXT, "");
        }

        try {
            String transformed = queryTransformer != null
                    ? queryTransformer.transform(userQuery, ctx)
                    : userQuery;

            List<Document> docs = documentRetriever != null
                    ? documentRetriever.retrieve(transformed, topK)
                    : Collections.<Document>emptyList();

            String augmented = queryAugmenter != null
                    ? queryAugmenter.augment(userQuery, docs)
                    : userQuery;

            return state.with(StateKeys.RAG_CONTEXT, augmented);
        } catch (RuntimeException e) {
            log.warn("RAG advisor failed, falling back to empty context: {}", e.getMessage());
            return state.with(StateKeys.RAG_CONTEXT, "");
        }
    }

    @Override
    public GraphState afterNode(String nodeName, GraphState state, Object ctx) throws InterruptException {
        return state;
    }
}
