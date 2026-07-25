package cn.watsontech.snapagent.core.rag;

import cn.watsontech.snapagent.core.vectorstore.Document;

import java.util.List;

/**
 * RAG third stage: augment the original query with retrieved context.
 *
 * <p>Implementations combine the user's query with the retrieved documents
 * to produce a context-rich prompt for the LLM.</p>
 */
public interface QueryAugmenter {

    /**
     * Augment the original query with retrieved documents.
     *
     * @param originalQuery the user's original query
     * @param retrievedDocs the retrieved documents (may be empty)
     * @return augmented prompt string
     */
    String augment(String originalQuery, List<Document> retrievedDocs);

    /**
     * Whether to return an empty string when no documents are retrieved.
     *
     * @return false (default) means return "no knowledge" instruction
     */
    default boolean isAllowEmptyContext() {
        return false;
    }
}
