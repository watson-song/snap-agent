package cn.watsontech.snapagent.core.vectorstore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-memory VectorStore for testing and development.
 *
 * <p>Stores documents in a list, computes cosine similarity for search.
 * Supports basic filterExpression: {@code key == 'value'}, {@code key != 'value'},
 * {@code key IN ['a', 'b']}, with AND combining.</p>
 */
public class InMemoryVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryVectorStore.class);

    private final List<Document> store = new ArrayList<Document>();

    @Override
    public synchronized void add(List<Document> documents) {
        if (documents != null) {
            store.addAll(documents);
        }
    }

    @Override
    public synchronized void delete(List<String> ids) {
        if (ids == null) return;
        store.removeIf(doc -> ids.contains(doc.getId()));
    }

    @Override
    public synchronized void clear() {
        store.clear();
    }

    @Override
    public synchronized List<Document> similaritySearch(SearchRequest request) {
        if (request == null || request.getQuery() == null || request.getQuery().trim().isEmpty()) {
            return Collections.emptyList();
        }

        String query = request.getQuery().trim();
        int topK = request.getTopK();
        double threshold = request.getSimilarityThreshold();
        String filter = request.getFilterExpression();

        List<Scored> scored = new ArrayList<Scored>();
        for (Document doc : store) {
            if (!matchesFilter(doc, filter)) {
                continue;
            }
            double score = computeSimilarity(query, doc);
            if (score >= threshold) {
                scored.add(new Scored(doc, score));
            }
        }

        scored.sort(Comparator.comparingDouble((Scored s) -> s.score).reversed());

        List<Document> result = new ArrayList<Document>();
        for (int i = 0; i < Math.min(topK, scored.size()); i++) {
            result.add(scored.get(i).doc);
        }
        return result;
    }

    public synchronized int size() {
        return store.size();
    }

    public synchronized List<Document> listAll() {
        return new ArrayList<Document>(store);
    }

    // --- similarity ---

    private double computeSimilarity(String query, Document doc) {
        if (doc.getEmbedding() != null) {
            return cosineSim(toVector(query), doc.getEmbedding());
        }
        // Fallback: simple text overlap
        return textOverlap(query, doc.getContent());
    }

    private float[] toVector(String text) {
        // Deterministic pseudo-embedding from text hash for testing
        float[] vec = new float[128];
        int hash = 0;
        for (char c : text.toCharArray()) {
            hash = hash * 31 + c;
        }
        java.util.Random rng = new java.util.Random(hash);
        float norm = 0;
        for (int i = 0; i < vec.length; i++) {
            vec[i] = (float) rng.nextGaussian();
            norm += vec[i] * vec[i];
        }
        norm = (float) Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < vec.length; i++) {
                vec[i] /= norm;
            }
        }
        return vec;
    }

    private double cosineSim(float[] a, float[] b) {
        int len = Math.min(a.length, b.length);
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < len; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private double textOverlap(String query, String content) {
        if (content == null || content.isEmpty()) return 0;
        String q = query.toLowerCase();
        String c = content.toLowerCase();
        int hits = 0;
        String[] tokens = q.split("\\s+");
        for (String t : tokens) {
            if (t.length() >= 2 && c.contains(t)) {
                hits++;
            }
        }
        return tokens.length > 0 ? (double) hits / tokens.length : 0;
    }

    // --- filter expression ---

    private static final Pattern EQ_PATTERN = Pattern.compile("(\\w+)\\s*==\\s*'([^']*)'");
    private static final Pattern NE_PATTERN = Pattern.compile("(\\w+)\\s*!=\\s*'([^']*)'");
    private static final Pattern IN_PATTERN = Pattern.compile("(\\w+)\\s+IN\\s*\\[([^\\]]*)\\]");

    private boolean matchesFilter(Document doc, String filter) {
        if (filter == null || filter.trim().isEmpty()) {
            return true;
        }
        try {
            String[] parts = filter.split("\\s+AND\\s+");
            for (String part : parts) {
                part = part.trim();
                if (!matchesSingleFilter(doc, part)) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            log.warn("FILTER_EXPRESSION_INVALID: '{}' — {}", filter, e.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private boolean matchesSingleFilter(Document doc, String expr) {
        Matcher m = EQ_PATTERN.matcher(expr);
        if (m.matches()) {
            String key = m.group(1);
            String val = m.group(2);
            Object meta = doc.getMetadata(key);
            return val.equals(String.valueOf(meta));
        }
        m = NE_PATTERN.matcher(expr);
        if (m.matches()) {
            String key = m.group(1);
            String val = m.group(2);
            Object meta = doc.getMetadata(key);
            return !val.equals(String.valueOf(meta));
        }
        m = IN_PATTERN.matcher(expr);
        if (m.matches()) {
            String key = m.group(1);
            String[] vals = m.group(2).split("\\s*,\\s*");
            Object meta = doc.getMetadata(key);
            String metaStr = String.valueOf(meta);
            for (String v : vals) {
                v = v.replaceAll("^'|'$", "");
                if (v.equals(metaStr)) return true;
            }
            return false;
        }
        // Unknown expression — treat as syntax error
        log.warn("FILTER_EXPRESSION_INVALID: '{}'", expr);
        return false;
    }

    private static class Scored {
        final Document doc;
        final double score;
        Scored(Document doc, double score) {
            this.doc = doc;
            this.score = score;
        }
    }
}
