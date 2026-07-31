package cn.watsontech.snapagent.core.vectorstore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Document — immutable metadata helper methods")
class DocumentTest {

    @Test
    @DisplayName("withMetadata adds a new key while preserving the original")
    void withMetadata_addsKey() {
        Map<String, Object> original = new LinkedHashMap<String, Object>();
        original.put("source", "handbook");
        Document doc = new Document("d1", "content", original, null);

        Document updated = doc.withMetadata("version", "1.2");

        // New metadata key is present on the derived document
        assertThat((String) updated.getMetadata("version")).isEqualTo("1.2");
        // Original document is preserved (immutable operation)
        assertThat(doc.getMetadata().keySet()).containsOnly("source");
        assertThat((String) doc.getMetadata("version")).isNull();
        // Existing keys are carried over to the derived document
        assertThat((String) updated.getMetadata("source")).isEqualTo("handbook");
        // Content and id are unchanged
        assertThat(updated.getId()).isEqualTo(doc.getId());
        assertThat(updated.getContent()).isEqualTo("content");
    }

    @Test
    @DisplayName("withMetadata overwrites an existing key value")
    void withMetadata_overwritesExistingKey() {
        Map<String, Object> original = new LinkedHashMap<String, Object>();
        original.put("version", "1.0");
        Document doc = new Document("d1", "content", original, null);

        Document updated = doc.withMetadata("version", "2.0");

        assertThat((String) updated.getMetadata("version")).isEqualTo("2.0");
        assertThat((String) doc.getMetadata("version")).isEqualTo("1.0");
    }

    @Test
    @DisplayName("getVersion reads the 'version' key from metadata")
    void getVersion_returnsFromMetadata() {
        Map<String, Object> meta = new LinkedHashMap<String, Object>();
        meta.put("version", "1.2.3");
        Document doc = new Document("d1", "content", meta, null);

        assertThat(doc.getVersion()).isEqualTo("1.2.3");
    }

    @Test
    @DisplayName("getVersion returns null when no version is set")
    void getVersion_returnsNullWhenAbsent() {
        Document doc = new Document("d1", "content", null, null);
        assertThat(doc.getVersion()).isNull();
    }

    @Test
    @DisplayName("getVersion coerces non-string values via toString()")
    void getVersion_coercesNonString() {
        Map<String, Object> meta = new LinkedHashMap<String, Object>();
        meta.put("version", 42);
        Document doc = new Document("d1", "content", meta, null);

        assertThat(doc.getVersion()).isEqualTo("42");
    }

    @Test
    @DisplayName("getProvenance reads the 'provenance' key from metadata")
    void getProvenance_returnsFromMetadata() {
        Map<String, Object> meta = new LinkedHashMap<String, Object>();
        meta.put("provenance", "gitlab:mr/42");
        Document doc = new Document("d1", "content", meta, null);

        assertThat(doc.getProvenance()).isEqualTo("gitlab:mr/42");
    }

    @Test
    @DisplayName("getProvenance returns null when no provenance is set")
    void getProvenance_returnsNullWhenAbsent() {
        Document doc = new Document("d1", "content", null, null);
        assertThat(doc.getProvenance()).isNull();
    }

    @Test
    @DisplayName("withMetadata can be chained to add multiple keys")
    void withMetadata_chains() {
        Document doc = new Document("d1", "content", null, null);

        Document updated = doc.withMetadata("source", "handbook")
                .withMetadata("version", "1.0")
                .withMetadata("provenance", "gitlab:mr/42");

        assertThat((String) updated.getMetadata("source")).isEqualTo("handbook");
        assertThat(updated.getVersion()).isEqualTo("1.0");
        assertThat(updated.getProvenance()).isEqualTo("gitlab:mr/42");
    }
}
