package cn.watsontech.snapagent.core.vectorstore;

import java.util.List;

/**
 * Vector store SPI for semantic search.
 *
 * <p>Replaces the old v0.7 {@code KnowledgeBase}. Implementations provide
 * concrete storage (e.g., Redis, JDBC, in-memory).</p>
 *
 * <p>Operations:</p>
 * <ul>
 *   <li>{@link #add} — store documents with embeddings</li>
 *   <li>{@link #delete} — remove documents by id</li>
 *   <li>{@link #similaritySearch} — semantic search returning ranked results</li>
 * </ul>
 */
public interface VectorStore {

    /**
     * Add documents to the store.
     *
     * @param documents documents to add (each should have embedding set)
     */
    void add(List<Document> documents);

    /**
     * Delete documents by id.
     *
     * @param ids document ids to delete
     */
    void delete(List<String> ids);

    /**
     * Semantic search: returns documents ranked by similarity to the query,
     * filtered by topK and similarityThreshold, optionally filtered by filterExpression.
     *
     * @param request search request
     * @return ranked list of documents (may be empty, never null)
     */
    List<Document> similaritySearch(SearchRequest request);
}
