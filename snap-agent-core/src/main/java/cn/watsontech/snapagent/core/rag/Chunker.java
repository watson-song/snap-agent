package cn.watsontech.snapagent.core.rag;

import cn.watsontech.snapagent.core.vectorstore.Document;

import java.util.List;

/**
 * SPI for splitting documents into chunks for embedding.
 *
 * <p>A chunker takes a single {@link Document} (typically the output of a
 * {@link DocumentReader}) and splits it into smaller, semantically coherent
 * chunks. Each chunk becomes its own {@link Document} in the VectorStore,
 * enabling fine-grained retrieval.</p>
 *
 * <p>P2-9: extracted from {@code KnowledgeETLPipeline.splitIntoChunks()}
 * to allow different chunking strategies (heading-based, fixed-size,
 * sentence-based, etc.) without modifying the pipeline.</p>
 *
 * <p>Known implementations:</p>
 * <ul>
 *   <li>{@code HeadingChunker} — splits by {@code ## } headings (boot2x starter)</li>
 * </ul>
 */
public interface Chunker {

    /**
     * Split a document into smaller chunks.
     *
     * @param document the document to split
     * @return list of chunk documents; may contain the original document
     *         unchanged if no split points are found
     */
    List<Document> chunk(Document document);

    /**
     * Chunker name/strategy (e.g., "heading", "fixed-size", "sentence").
     *
     * @return the strategy name
     */
    String strategy();
}
