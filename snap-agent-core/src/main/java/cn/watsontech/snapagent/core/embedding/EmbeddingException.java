package cn.watsontech.snapagent.core.embedding;

/**
 * Thrown when an {@link EmbeddingModel} fails to compute an embedding
 * (network error, non-200 response, or malformed response body).
 *
 * <p>Implementations must NOT silently return an all-zero vector on failure —
 * that would poison the {@link cn.watsontech.snapagent.core.vectorstore.VectorStore}
 * and make retrieval results silently wrong. Throwing lets the caller (e.g. the
 * ETL pipeline) skip the chunk and log the failure explicitly.</p>
 */
public class EmbeddingException extends RuntimeException {

    public EmbeddingException(String message) {
        super(message);
    }

    public EmbeddingException(String message, Throwable cause) {
        super(message, cause);
    }
}
