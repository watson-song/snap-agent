package cn.watsontech.snapagent.core.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 知识库, 管理 {@link KnowledgeSource} 列表 + 提供检索接口。
 *
 * <p>On construction, loads all fragments from every source and caches them
 * in memory. {@link #reload()} re-reads all sources (for hot-reload
 * scenarios). Search delegates scoring to the {@link KnowledgeSearcher} and
 * returns the top-K fragments above a minimum score threshold.</p>
 */
public class KnowledgeBase {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBase.class);

    private final List<KnowledgeSource> sources;
    private final KnowledgeSearcher searcher;
    private volatile List<KnowledgeFragment> allFragments;

    /**
     * Construct a knowledge base, loading fragments from all sources immediately.
     *
     * @param sources  knowledge sources (may be empty)
     * @param searcher scoring algorithm
     */
    public KnowledgeBase(List<KnowledgeSource> sources, KnowledgeSearcher searcher) {
        this.sources = sources != null ? new ArrayList<KnowledgeSource>(sources)
                : new ArrayList<KnowledgeSource>();
        this.searcher = searcher;
        this.allFragments = loadAll();
    }

    /**
     * 检索: 返回与 query 最相关的 topK 个片段 (score >= minScore)。
     *
     * @param query    the user's query text
     * @param topK     maximum number of fragments to return
     * @param minScore minimum relevance score threshold
     * @return ranked list of matching fragments (may be empty, never null)
     */
    public List<KnowledgeFragment> search(String query, int topK, double minScore) {
        List<SearchResult> scored = searchWithScores(query, topK, minScore);
        List<KnowledgeFragment> result = new ArrayList<KnowledgeFragment>();
        for (SearchResult sr : scored) {
            result.add(sr.getFragment());
        }
        return result;
    }

    /**
     * 检索 (with scores): 返回与 query 最相关的 topK 个片段及其分数 (score >= minScore)。
     *
     * @param query    the user's query text
     * @param topK     maximum number of fragments to return
     * @param minScore minimum relevance score threshold
     * @return ranked list of search results (may be empty, never null)
     */
    public List<SearchResult> searchWithScores(String query, int topK, double minScore) {
        if (query == null || query.isEmpty() || allFragments.isEmpty()) {
            return new ArrayList<SearchResult>();
        }
        List<ScoredFragment> scored = new ArrayList<ScoredFragment>();
        for (KnowledgeFragment f : allFragments) {
            double s = searcher.score(query, f);
            if (s >= minScore) {
                scored.add(new ScoredFragment(f, s));
            }
        }
        Collections.sort(scored, (a, b) -> Double.compare(b.score, a.score));
        List<SearchResult> result = new ArrayList<SearchResult>();
        for (int i = 0; i < Math.min(topK, scored.size()); i++) {
            result.add(new SearchResult(scored.get(i).fragment, scored.get(i).score));
        }
        return result;
    }

    /**
     * 重新加载所有源 (热重载)。
     *
     * <p>Calls {@link KnowledgeSource#reload()} on each source first (to
     * invalidate any internal caches), then re-reads all fragments via
     * {@link KnowledgeSource#load()}.</p>
     */
    public void reload() {
        for (KnowledgeSource source : sources) {
            try {
                source.reload();
            } catch (Exception e) {
                log.warn("Knowledge source {} reload failed: {}", source.type(), e.getMessage());
            }
        }
        this.allFragments = loadAll();
    }

    /**
     * @return the total number of cached fragments
     */
    public int size() {
        return allFragments.size();
    }

    private List<KnowledgeFragment> loadAll() {
        List<KnowledgeFragment> all = new ArrayList<KnowledgeFragment>();
        for (KnowledgeSource source : sources) {
            try {
                List<KnowledgeFragment> fragments = source.load();
                if (fragments != null) {
                    all.addAll(fragments);
                }
            } catch (Exception e) {
                log.warn("Knowledge source {} load failed: {}", source.type(), e.getMessage());
            }
        }
        return all;
    }

    private static class ScoredFragment {
        final KnowledgeFragment fragment;
        final double score;

        ScoredFragment(KnowledgeFragment f, double s) {
            fragment = f;
            score = s;
        }
    }
}
