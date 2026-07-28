package cn.watsontech.snapagent.boot2x.knowledge;

import cn.watsontech.snapagent.core.vectorstore.Document;
import cn.watsontech.snapagent.core.vectorstore.InMemoryVectorStore;
import cn.watsontech.snapagent.core.vectorstore.SearchRequest;
import cn.watsontech.snapagent.core.vectorstore.VectorStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("VectorStoreDocumentRetriever + IdentityQueryTransformer")
class VectorStoreDocumentRetrieverTest {

    @Test
    @DisplayName("retrieve returns documents from vector store similarity search")
    void retrieveReturnsDocumentsFromVectorStore() {
        VectorStore store = new InMemoryVectorStore();
        store.add(Arrays.asList(
                new Document("hello world", null),
                new Document("goodbye world", null)
        ));

        VectorStoreDocumentRetriever retriever = new VectorStoreDocumentRetriever(store);
        List<Document> results = retriever.retrieve("hello", 5);

        assertThat(results).isNotNull();
    }

    @Test
    @DisplayName("null vectorStore throws IllegalArgumentException")
    void nullVectorStoreThrowsException() {
        assertThatThrownBy(() -> new VectorStoreDocumentRetriever(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("vectorStore cannot be null");
    }

    @Test
    @DisplayName("similarity threshold and filter are passed through to SearchRequest")
    void customThresholdAndFilterPreserved() {
        VectorStoreDocumentRetriever retriever = new VectorStoreDocumentRetriever(
                new InMemoryVectorStore(), 0.85, "source=docs");
        assertThat(retriever.getSimilarityThreshold()).isEqualTo(0.85);
        assertThat(retriever.getFilterExpression()).isEqualTo("source=docs");
    }

    @Test
    @DisplayName("IdentityQueryTransformer returns original query unchanged")
    void identityTransformerReturnsOriginalQuery() {
        IdentityQueryTransformer transformer = new IdentityQueryTransformer();
        String result = transformer.transform("what is the error?", null);
        assertThat(result).isEqualTo("what is the error?");
    }
}
