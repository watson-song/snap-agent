package cn.watsontech.snapagent.core.embedding;

import java.util.ArrayList;
import java.util.List;

/**
 * Embedding model SPI for computing vector representations of text.
 *
 * <p>Implementations (e.g., OpenAI, Ollama) are provided by the starter layer.
 * Used by {@link cn.watsontech.snapagent.core.vectorstore.VectorStore} for
 * semantic search and by the ETL pipeline for batch embedding.</p>
 */
public interface EmbeddingModel {

    /**
     * Embed a single text into a float vector.
     *
     * @param text the text to embed
     * @return float[] vector representation
     * @throws IllegalArgumentException if text is null
     */
    float[] embed(String text);

    /**
     * Embed a batch of texts into vectors.
     *
     * @param texts list of texts to embed
     * @return list of float[] vectors (same order as input)
     * @throws IllegalArgumentException if any text is null
     */
    List<float[]> embedBatch(List<String> texts);
}
