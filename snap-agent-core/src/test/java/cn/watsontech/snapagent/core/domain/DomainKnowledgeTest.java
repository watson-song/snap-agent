package cn.watsontech.snapagent.core.domain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link DomainKnowledge} value object.
 */
class DomainKnowledgeTest {

    @Test
    void shouldCreateWithAllFields() {
        Map<String, Object> meta = new HashMap<String, Object>();
        meta.put("version", 1);

        DomainKnowledge dk = new DomainKnowledge(
                "调拨计划", "业务描述内容",
                Arrays.asList("drp_allocation_plan", "drp_allocation_detail"),
                Arrays.asList("AllocationPlanService", "BalanceAlgorithmService"),
                Arrays.asList("ReplenishmentPlanTask.generate()"),
                Arrays.asList("补货策略", "安全库存"),
                Arrays.asList("replenishment", "allocation"),
                meta);

        assertThat(dk.getName()).isEqualTo("调拨计划");
        assertThat(dk.getContent()).isEqualTo("业务描述内容");
        assertThat(dk.getTables()).hasSize(2);
        assertThat(dk.getTables()).contains("drp_allocation_plan");
        assertThat(dk.getServices()).hasSize(2);
        assertThat(dk.getEntryPoints()).containsExactly("ReplenishmentPlanTask.generate()");
        assertThat(dk.getRelatedConcepts()).containsExactly("补货策略", "安全库存");
        assertThat(dk.getTags()).containsExactly("replenishment", "allocation");
        assertThat(dk.getRawMetadata()).containsEntry("version", 1);
    }

    @Test
    void shouldHandleNullLists() {
        DomainKnowledge dk = new DomainKnowledge("test", "content",
                null, null, null, null, null, null);

        assertThat(dk.getTables()).isEmpty();
        assertThat(dk.getServices()).isEmpty();
        assertThat(dk.getEntryPoints()).isEmpty();
        assertThat(dk.getRelatedConcepts()).isEmpty();
        assertThat(dk.getTags()).isEmpty();
        assertThat(dk.getRawMetadata()).isEmpty();
    }

    @Test
    void listsShouldBeUnmodifiable() {
        DomainKnowledge dk = new DomainKnowledge("test", "content",
                Arrays.asList("t1"), null, null, null, null, null);

        try {
            dk.getTables().add("t2");
            assertThat(false).as("Should have thrown").isTrue();
        } catch (UnsupportedOperationException e) {
            // expected
        }
    }

    @Test
    void toSummaryShouldIncludeAllStructuredFields() {
        DomainKnowledge dk = new DomainKnowledge(
                "调拨计划", "详细描述",
                Arrays.asList("drp_allocation_plan"),
                Arrays.asList("AllocationPlanService"),
                Arrays.asList("Task.generate()"),
                Arrays.asList("补货策略"),
                null, null);

        String summary = dk.toSummary();
        assertThat(summary).contains("概念: 调拨计划");
        assertThat(summary).contains("涉及表: drp_allocation_plan");
        assertThat(summary).contains("涉及类: AllocationPlanService");
        assertThat(summary).contains("入口方法: Task.generate()");
        assertThat(summary).contains("关联概念: 补货策略");
        assertThat(summary).contains("详细描述");
    }

    @Test
    void toSummaryShouldOmitEmptyFields() {
        DomainKnowledge dk = new DomainKnowledge("简单概念", "内容",
                null, null, null, null, null, null);

        String summary = dk.toSummary();
        assertThat(summary).contains("概念: 简单概念");
        assertThat(summary).doesNotContain("涉及表");
        assertThat(summary).doesNotContain("涉及类");
    }
}
