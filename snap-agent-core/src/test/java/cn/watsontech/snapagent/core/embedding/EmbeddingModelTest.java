package cn.watsontech.snapagent.core.embedding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("EmbeddingModel — embed + embedBatch")
class EmbeddingModelTest {

    // A simple test EmbeddingModel that produces deterministic vectors
    private final EmbeddingModel model = new EmbeddingModel() {
        @Override
        public float[] embed(String text) {
            if (text == null) throw new IllegalArgumentException("text cannot be null");
            float[] vec = new float[1536];
            int hash = text.hashCode();
            for (int i = 0; i < vec.length; i++) {
                vec[i] = (float) Math.sin(hash + i) * 0.5f;
            }
            return vec;
        }

        @Override
        public List<float[]> embedBatch(List<String> texts) {
            if (texts == null) throw new IllegalArgumentException("texts cannot be null");
            if (texts.isEmpty()) return Arrays.asList();
            List<float[]> result = new java.util.ArrayList<float[]>();
            for (String text : texts) {
                if (text == null) throw new IllegalArgumentException("text element cannot be null");
                result.add(embed(text));
            }
            return result;
        }
    };

    // UC-04: embed single text
    @Test
    @DisplayName("embed('hello') → float[1536] 每个元素在 [-1,1]")
    void shouldEmbedSingleText() {
        float[] vec = model.embed("hello");
        assertThat(vec).hasSize(1536);
        for (float v : vec) {
            assertThat(v).isBetween(-1.0f, 1.0f);
        }
    }

    // UC-05: embedBatch
    @Test
    @DisplayName("embedBatch(['a','b','c']) → 3 个向量每个 dim=1536")
    void shouldEmbedBatch() {
        List<float[]> vecs = model.embedBatch(Arrays.asList("a", "b", "c"));
        assertThat(vecs).hasSize(3);
        for (float[] v : vecs) {
            assertThat(v).hasSize(1536);
        }
    }

    // UC-06: null/empty boundary
    @Test
    @DisplayName("embed(null) → IllegalArgumentException")
    void shouldThrowOnNullEmbed() {
        assertThatThrownBy(() -> model.embed(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("embedBatch([]) → 空列表不抛异常")
    void shouldReturnEmptyForEmptyBatch() {
        List<float[]> result = model.embedBatch(Arrays.asList());
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("embedBatch 含 null → IllegalArgumentException")
    void shouldThrowOnNullElement() {
        assertThatThrownBy(() -> model.embedBatch(Arrays.asList("a", null, "c")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
