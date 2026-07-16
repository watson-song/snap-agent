package cn.watsontech.snapagent.core.knowledge;

/**
 * 检索算法 SPI。v0.7 提供 {@code SimpleKeywordSearcher}, v0.7.2 可替换为向量检索。
 *
 * <p>Implementations score the relevance of a knowledge fragment to a given
 * query. The score must be in the range [0.0, 1.0], where 0.0 means no
 * relevance and 1.0 means perfect match.</p>
 */
public interface KnowledgeSearcher {

    /**
     * Score the relevance of a fragment to the query.
     *
     * @param query    the user's query text
     * @param fragment the knowledge fragment to score
     * @return relevance score in [0.0, 1.0]
     */
    double score(String query, KnowledgeFragment fragment);
}
