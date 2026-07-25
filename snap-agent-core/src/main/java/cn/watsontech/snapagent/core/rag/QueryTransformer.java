package cn.watsontech.snapagent.core.rag;

/**
 * RAG first stage: rewrite the user's query for better retrieval.
 *
 * <p>Implementations can expand, compress, or decompose the query
 * (e.g., multi-query, HyDE, query expansion with synonyms).</p>
 */
@FunctionalInterface
public interface QueryTransformer {

    /**
     * Transform the original query into a (possibly different) query string.
     *
     * @param originalQuery the user's original query
     * @param ctx optional execution context (may be null)
     * @return the transformed query
     */
    String transform(String originalQuery, Object ctx);
}
