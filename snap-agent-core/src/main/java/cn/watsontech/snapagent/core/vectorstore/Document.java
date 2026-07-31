package cn.watsontech.snapagent.core.vectorstore;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable knowledge document with id, content, metadata, and optional embedding.
 *
 * <p>Replaces the old v0.7 {@code KnowledgeFragment}. Used by {@link VectorStore}
 * for semantic search and by the RAG pipeline for context injection.</p>
 */
public final class Document {

    private final String id;
    private final String content;
    private final Map<String, Object> metadata;
    private final float[] embedding;

    public Document(String content) {
        this(UUID.randomUUID().toString(), content, null, null);
    }

    public Document(String content, Map<String, Object> metadata) {
        this(UUID.randomUUID().toString(), content, metadata, null);
    }

    public Document(String id, String content, Map<String, Object> metadata, float[] embedding) {
        this.id = id;
        this.content = content;
        this.metadata = metadata == null
                ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(metadata);
        this.embedding = embedding;
    }

    public String getId() { return id; }
    public String getContent() { return content; }
    public Map<String, Object> getMetadata() { return Collections.unmodifiableMap(metadata); }
    public float[] getEmbedding() { return embedding; }

    @SuppressWarnings("unchecked")
    public <T> T getMetadata(String key) {
        return (T) metadata.get(key);
    }

    /**
     * Convenience accessor for the {@code version} metadata key.
     *
     * @return the version string, or {@code null} if not set
     */
    public String getVersion() {
        Object v = metadata.get("version");
        return v != null ? v.toString() : null;
    }

    /**
     * Convenience accessor for the {@code provenance} metadata key.
     *
     * @return the provenance string, or {@code null} if not set
     */
    public String getProvenance() {
        Object v = metadata.get("provenance");
        return v != null ? v.toString() : null;
    }

    public Document withEmbedding(float[] embedding) {
        return new Document(id, content, metadata, embedding);
    }

    /**
     * Return a new Document with an additional metadata entry. The original
     * document is unchanged (immutable). Allows chaining:
     * {@code doc.withMetadata("k", v).withMetadata("k2", v2)}.
     *
     * @param key   metadata key to add or overwrite
     * @param value metadata value
     * @return a new Document with the updated metadata
     */
    public Document withMetadata(String key, Object value) {
        Map<String, Object> newMeta = new LinkedHashMap<String, Object>(this.metadata);
        newMeta.put(key, value);
        return new Document(id, content, newMeta, embedding);
    }

    @Override
    public String toString() {
        return "Document{id='" + id + "', content=" + (content != null ? content.length() + " chars" : "null") + "}";
    }
}
