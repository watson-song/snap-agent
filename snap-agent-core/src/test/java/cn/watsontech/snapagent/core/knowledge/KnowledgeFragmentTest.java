package cn.watsontech.snapagent.core.knowledge;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link KnowledgeFragment}.
 */
class KnowledgeFragmentTest {

    @Test
    void shouldReturnConstructorValuesFromGetters() {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("category", "database");
        metadata.put("tag", "mysql");

        KnowledgeFragment fragment = new KnowledgeFragment(
                "补货策略生成规则", "最低库存阈值不低于 100", "business-overview.md:section-3",
                metadata);

        assertThat(fragment.getTitle()).isEqualTo("补货策略生成规则");
        assertThat(fragment.getContent()).isEqualTo("最低库存阈值不低于 100");
        assertThat(fragment.getSource()).isEqualTo("business-overview.md:section-3");
        assertThat(fragment.getMetadata()).containsEntry("category", "database");
        assertThat(fragment.getMetadata()).containsEntry("tag", "mysql");
        assertThat(fragment.getMetadata()).hasSize(2);
    }

    @Test
    void shouldHaveMeaningfulToString() {
        KnowledgeFragment fragment = new KnowledgeFragment(
                "数据库诊断", "连接池打满检查", "db.md:section-1", null);

        String str = fragment.toString();
        assertThat(str).contains("KnowledgeFragment");
        assertThat(str).contains("数据库诊断");
        assertThat(str).contains("db.md:section-1");
    }

    @Test
    void shouldDefensivelyCopyMetadataOnConstruction() {
        Map<String, String> original = new HashMap<>();
        original.put("key", "value");

        KnowledgeFragment fragment = new KnowledgeFragment("t", "c", "s", original);

        // Modify the original map — fragment's copy should be unaffected
        original.put("key", "changed");
        original.put("new-key", "new-value");

        assertThat(fragment.getMetadata().get("key")).isEqualTo("value");
        assertThat(fragment.getMetadata()).doesNotContainKey("new-key");
    }

    @Test
    void shouldReturnUnmodifiableMetadataFromGetter() {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("key", "value");

        KnowledgeFragment fragment = new KnowledgeFragment("t", "c", "s", metadata);

        assertThatThrownBy(() -> fragment.getMetadata().put("hack", "x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldHaveEmptyMetadataWhenNullPassed() {
        KnowledgeFragment fragment = new KnowledgeFragment("t", "c", "s", null);

        assertThat(fragment.getMetadata()).isNotNull();
        assertThat(fragment.getMetadata()).isEmpty();
    }
}
