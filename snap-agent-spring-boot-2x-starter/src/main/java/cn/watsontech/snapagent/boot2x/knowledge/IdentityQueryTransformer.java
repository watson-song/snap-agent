package cn.watsontech.snapagent.boot2x.knowledge;

import cn.watsontech.snapagent.core.rag.QueryTransformer;

/**
 * Pass-through {@link QueryTransformer} that returns the original query
 * unchanged.
 *
 * <p>This is the default when no query rewriting (HyDE, multi-query,
 * synonym expansion, etc.) is needed. The RAG pipeline retrieves
 * documents using the exact user query string.</p>
 */
public class IdentityQueryTransformer implements QueryTransformer {

    @Override
    public String transform(String originalQuery, Object ctx) {
        return originalQuery;
    }
}
