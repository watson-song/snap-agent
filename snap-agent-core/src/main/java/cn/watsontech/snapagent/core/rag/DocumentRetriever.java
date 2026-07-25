package cn.watsontech.snapagent.core.rag;

import cn.watsontech.snapagent.core.vectorstore.Document;

import java.util.List;

/**
 * RAG second stage: retrieve relevant documents from the vector store.
 *
 * <p>Implementations call {@link cn.watsontech.snapagent.core.vectorstore.VectorStore#similaritySearch}
 * with the (possibly transformed) query and return the top-K documents.</p>
 */
@FunctionalInterface
public interface DocumentRetriever {

    /**
     * Retrieve documents relevant to the query.
     *
     * @param query the (possibly transformed) query
     * @param topK maximum number of documents to return
     * @return list of documents (may be empty, never null)
     */
    List<Document> retrieve(String query, int topK);
}
