package cn.watsontech.snapagent.core.vectorstore;

/**
 * Search request for {@link VectorStore#similaritySearch(SearchRequest)}.
 *
 * <p>Carries the query text, topK limit, similarityThreshold, and an optional
 * filterExpression for metadata filtering.</p>
 *
 * <p>Default values: topK=4, similarityThreshold=0.75, filterExpression=null.</p>
 */
public final class SearchRequest {

    private final String query;
    private final int topK;
    private final double similarityThreshold;
    private final String filterExpression;

    public SearchRequest(String query) {
        this(query, 4, 0.75, null);
    }

    public SearchRequest(String query, int topK, double similarityThreshold, String filterExpression) {
        this.query = query;
        this.topK = topK;
        this.similarityThreshold = similarityThreshold;
        this.filterExpression = filterExpression;
    }

    public static SearchRequest from(SearchRequest request) {
        return new SearchRequest(request.getQuery(), request.getTopK(),
                request.getSimilarityThreshold(), request.getFilterExpression());
    }

    public static SearchRequest query(String query) {
        return new SearchRequest(query);
    }

    public String getQuery() { return query; }
    public int getTopK() { return topK; }
    public double getSimilarityThreshold() { return similarityThreshold; }
    public String getFilterExpression() { return filterExpression; }

    public SearchRequest withTopK(int topK) {
        return new SearchRequest(query, topK, similarityThreshold, filterExpression);
    }

    public SearchRequest withSimilarityThreshold(double threshold) {
        return new SearchRequest(query, topK, threshold, filterExpression);
    }

    public SearchRequest withFilterExpression(String filterExpression) {
        return new SearchRequest(query, topK, similarityThreshold, filterExpression);
    }

    @Override
    public String toString() {
        return "SearchRequest{query='" + query + "', topK=" + topK
                + ", threshold=" + similarityThreshold
                + ", filter='" + filterExpression + "'}";
    }
}
