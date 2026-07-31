package cn.watsontech.snapagent.boot2x.knowledge;

import cn.watsontech.snapagent.core.embedding.EmbeddingModel;
import cn.watsontech.snapagent.core.rag.Chunker;
import cn.watsontech.snapagent.core.rag.DocumentReader;
import cn.watsontech.snapagent.core.vectorstore.Document;
import cn.watsontech.snapagent.core.vectorstore.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * ETL pipeline: {@link DocumentReader} → {@link Chunker} → embed → write to VectorStore.
 *
 * <p>Reads a file via a pluggable {@link DocumentReader}, splits each resulting
 * document via a pluggable {@link Chunker}, embeds each chunk via
 * {@link EmbeddingModel#embed}, and writes all documents to
 * {@link VectorStore#add}.</p>
 *
 * <p>P2-9: refactored to use the {@code DocumentReader} + {@code Chunker} SPIs.
 * The default constructor creates a {@link MarkdownDocumentReader} +
 * {@link HeadingChunker} for backward compatibility with the original
 * Markdown-only pipeline.</p>
 *
 * <p>Single-chunk failure isolation: if {@code VectorStore.add} throws for one chunk,
 * the exception is caught + WARN logged, and remaining chunks continue.</p>
 */
public class KnowledgeETLPipeline {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeETLPipeline.class);

    private final VectorStore vectorStore;
    private final EmbeddingModel embeddingModel;
    private final DocumentReader reader;
    private final Chunker chunker;

    /**
     * Backward-compatible constructor: uses {@link MarkdownDocumentReader} +
     * {@link HeadingChunker}.
     *
     * @param vectorStore    the target vector store
     * @param embeddingModel the embedding model
     */
    public KnowledgeETLPipeline(VectorStore vectorStore, EmbeddingModel embeddingModel) {
        this(vectorStore, embeddingModel, new MarkdownDocumentReader(), new HeadingChunker());
    }

    /**
     * Full constructor with pluggable reader and chunker.
     *
     * @param vectorStore    the target vector store
     * @param embeddingModel the embedding model
     * @param reader         the document reader (file → raw documents)
     * @param chunker        the chunker (raw document → chunks)
     */
    public KnowledgeETLPipeline(VectorStore vectorStore, EmbeddingModel embeddingModel,
                                DocumentReader reader, Chunker chunker) {
        this.vectorStore = vectorStore;
        this.embeddingModel = embeddingModel;
        this.reader = reader != null ? reader : new MarkdownDocumentReader();
        this.chunker = chunker != null ? chunker : new HeadingChunker();
    }

    /**
     * Run ETL on a single file.
     *
     * @param file the file to process
     * @return number of documents written to VectorStore
     */
    public int run(Path file) {
        if (file == null) return 0;

        List<Document> rawDocs = reader.read(file);
        if (rawDocs.isEmpty()) return 0;

        String fileName = file.getFileName().toString();
        int written = 0;
        int totalChunks = 0;

        for (Document rawDoc : rawDocs) {
            List<Document> chunks = chunker.chunk(rawDoc);
            totalChunks += chunks.size();
            for (Document chunk : chunks) {
                try {
                    Document doc = chunk;
                    if (embeddingModel != null) {
                        float[] embedding = embeddingModel.embed(doc.getContent());
                        doc = doc.withEmbedding(embedding);
                    }
                    if (vectorStore != null) {
                        vectorStore.add(Collections.singletonList(doc));
                        written++;
                    }
                } catch (RuntimeException e) {
                    log.warn("ETL chunk failed in {}: {}", fileName, e.getMessage());
                }
            }
        }
        log.info("ETL loaded {} documents from {} ({} chunks)", written, fileName, totalChunks);
        return written;
    }

    /**
     * Run ETL on all files matching the reader's supported extension in a directory.
     *
     * @param dir directory containing files to process
     * @return total documents written
     */
    public int runAll(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return 0;
        int total = 0;
        String ext = reader.supportedExtension();
        List<Path> files = new ArrayList<Path>();
        // P2-17: Files.walk returns a Stream that holds file handles; it must
        // be closed via try-with-resources to avoid resource leaks.
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().toLowerCase().endsWith("." + ext))
                    .forEach(files::add);
        } catch (IOException e) {
            log.warn("Failed to scan directory {}: {}", dir, e.getMessage());
            return 0;
        }
        for (Path file : files) {
            try {
                total += run(file);
            } catch (RuntimeException e) {
                log.warn("ETL failed for {}: {}", file.getFileName(), e.getMessage());
            }
        }
        return total;
    }
}
