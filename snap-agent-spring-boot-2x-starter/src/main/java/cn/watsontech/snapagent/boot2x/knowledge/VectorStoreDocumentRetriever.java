package cn.watsontech.snapagent.boot2x.knowledge;

import cn.watsontech.snapagent.core.rag.DocumentRetriever;
import cn.watsontech.snapagent.core.vectorstore.Document;
import cn.watsontech.snapagent.core.vectorstore.SearchRequest;
import cn.watsontech.snapagent.core.vectorstore.VectorStore;

import java.util.List;

/**
 * {@link DocumentRetriever} backed by a {@link VectorStore}.
 *
 * <p>Adapts the vector store's {@link VectorStore#similaritySearch} method
 * to the {@link DocumentRetriever} SPI, with a configurable similarity
 * threshold (default 0.75).</p>
 */
public class VectorStoreDocumentRetriever implements DocumentRetriever {

    private final VectorStore vectorStore;
    private final double similarityThreshold;
    private final String filterExpression;

    /**
     * Construct with default similarity threshold (0.75) and no filter.
     */
    public VectorStoreDocumentRetriever(VectorStore vectorStore) {
        this(vectorStore, 0.75, null);
    }

    /**
     * Construct with a custom similarity threshold and optional filter.
     *
     * @param vectorStore         the vector store to query
     * @param similarityThreshold minimum cosine similarity for results
     * @param filterExpression    optional metadata filter expression (may be null)
     */
    public VectorStoreDocumentRetriever(VectorStore vectorStore,
                                       double similarityThreshold,
                                       String filterExpression) {
        if (vectorStore == null) {
            throw new IllegalArgumentException("vectorStore cannot be null");
        }
        this.vectorStore = vectorStore;
        this.similarityThreshold = similarityThreshold;
        this.filterExpression = filterExpression;
    }

    @Override
    public List<Document> retrieve(String query, int topK) {
        return vectorStore.similaritySearch(
                new SearchRequest(query, topK, similarityThreshold, filterExpression));
    }

    public double getSimilarityThreshold() {
        return similarityThreshold;
    }

    public String getFilterExpression() {
        return filterExpression;
    }
}
