package cn.watsontech.snapagent.core.rag;

import cn.watsontech.snapagent.core.vectorstore.Document;

import java.nio.file.Path;
import java.util.List;

/**
 * SPI for reading documents from various source formats.
 *
 * <p>Implementations parse a specific file format (Markdown, PDF, HTML, etc.)
 * into one or more raw {@link Document} instances. The ETL pipeline then
 * passes these documents to a {@link Chunker} for splitting before
 * embedding and storage.</p>
 *
 * <p>P2-9: extracted from {@code KnowledgeETLPipeline} to allow plugging in
 * new readers without modifying the pipeline.</p>
 *
 * <p>Known implementations:</p>
 * <ul>
 *   <li>{@code MarkdownDocumentReader} — reads {@code .md} files (boot2x starter)</li>
 * </ul>
 */
public interface DocumentReader {

    /**
     * Read a file and return raw documents (one per file or section).
     *
     * @param file the file to read
     * @return list of documents; empty list if the file cannot be read
     */
    List<Document> read(Path file);

    /**
     * Supported file extension (e.g., "md", "pdf").
     *
     * @return the extension without the leading dot
     */
    String supportedExtension();
}
