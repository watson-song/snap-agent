package cn.watsontech.snapagent.core.rag;

import cn.watsontech.snapagent.core.vectorstore.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DefaultQueryAugmenter — CJK-aware token estimation")
class DefaultQueryAugmenterTest {

    private final DefaultQueryAugmenter augmenter = new DefaultQueryAugmenter();

    @Test
    @DisplayName("estimateTokens(null) 和 estimateTokens('') → 0")
    void estimateTokens_nullOrEmpty_returnsZero() {
        assertThat(augmenter.estimateTokens(null)).isEqualTo(0);
        assertThat(augmenter.estimateTokens("")).isEqualTo(0);
        assertThat(augmenter.estimateTokens("   ")).isEqualTo(0);
    }

    @Test
    @DisplayName("英文按单词计数: 'connection pool config' → 3 tokens")
    void estimateTokens_englishWords() {
        assertThat(augmenter.estimateTokens("connection pool config")).isEqualTo(3);
    }

    @Test
    @DisplayName("CJK 每字符计 1 token: '连接池' → 3 tokens")
    void estimateTokens_cjkChars() {
        assertThat(augmenter.estimateTokens("连接池")).isEqualTo(3);
    }

    @Test
    @DisplayName("中英混合: '配置 connection 池' → 4 tokens (配/置=2, connection=1, 池=1)")
    void estimateTokens_mixedCjkAndEnglish() {
        assertThat(augmenter.estimateTokens("配置 connection 池")).isEqualTo(4);
    }

    @Test
    @DisplayName("标点符号单独计数: 'a, b.' → 4 tokens (a, 逗号, b, 句号)")
    void estimateTokens_punctuationCounted() {
        // a(1) + ,(1) + b(1) + .(1) = 4
        assertThat(augmenter.estimateTokens("a, b.")).isEqualTo(4);
    }

    @Test
    @DisplayName("数字串作为一个 token: 'v2 1024' → 2 tokens")
    void estimateTokens_digitRuns() {
        assertThat(augmenter.estimateTokens("v2 1024")).isEqualTo(2);
    }

    @Test
    @DisplayName("CJK 长文本 token 数大于 chars/3.5 启发式估算")
    void estimateTokens_cjkMoreAccurateThanCharHeuristic() {
        String text = "连接池配置说明连接超时处理"; // 13 CJK characters
        int estimated = augmenter.estimateTokens(text);
        // The old chars/3.5 heuristic would give 13/3.5 ≈ 3, but each CJK char
        // is roughly one token, so the CJK-aware estimate must be larger.
        assertThat(estimated).isGreaterThan((int) (text.length() / 3.5));
        assertThat(estimated).isEqualTo(13);
    }

    @Test
    @DisplayName("augment 输出仍正确包含检索到的文档内容")
    void augment_includesDocContent() {
        Document doc = new Document("d1", "连接池配置: max=20", null, null);
        String result = augmenter.augment("如何配置", Arrays.asList(doc));
        assertThat(result).contains("如何配置");
        assertThat(result).contains("连接池配置: max=20");
    }

    @Test
    @DisplayName("augment 空文档 → 含 '无相关知识' 指令")
    void augment_emptyDocs_injectsNoKnowledge() {
        String result = augmenter.augment("测试", Collections.<Document>emptyList());
        assertThat(result).contains("无相关知识");
    }
}
