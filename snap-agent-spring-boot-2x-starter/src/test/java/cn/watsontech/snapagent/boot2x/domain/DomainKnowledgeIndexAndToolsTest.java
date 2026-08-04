package cn.watsontech.snapagent.boot2x.domain;

import cn.watsontech.snapagent.core.domain.DomainKnowledge;
import cn.watsontech.snapagent.core.tool.ToolCallback;
import cn.watsontech.snapagent.core.tool.ToolCallbacks;
import cn.watsontech.snapagent.core.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link InMemoryDomainKnowledgeIndex} and {@link DomainKnowledgeTools}.
 */
class DomainKnowledgeIndexAndToolsTest {

    private InMemoryDomainKnowledgeIndex index;
    private DomainKnowledgeTools tools;
    private ToolCallback[] callbacks;

    @BeforeEach
    void setUp() {
        index = new InMemoryDomainKnowledgeIndex();

        // Add test concepts
        index.put(new DomainKnowledge("调拨计划", "调拨详细描述",
                Arrays.asList("drp_allocation_plan", "drp_allocation_detail"),
                Arrays.asList("AllocationPlanService", "BalanceAlgorithmService"),
                Arrays.asList("ReplenishmentPlanTask.generate()"),
                Arrays.asList("补货策略", "安全库存"),
                Arrays.asList("replenishment"), null));

        index.put(new DomainKnowledge("补货策略", "补货详细描述",
                Arrays.asList("replm_inv_param_sku_wh_input"),
                Arrays.asList("ReplenishmentStrategyService"),
                null, Arrays.asList("调拨计划"), null, null));

        index.put(new DomainKnowledge("安全库存", "安全库存详细描述",
                Arrays.asList("replm_safety_stock"),
                Arrays.asList("SafetyStockService"),
                null, Arrays.asList("调拨计划", "补货策略"), null, null));

        tools = new DomainKnowledgeTools(index);
        callbacks = ToolCallbacks.from(tools);
    }

    private ToolCallback findByName(String name) {
        for (ToolCallback cb : callbacks) {
            if (cb.getName().equals(name)) return cb;
        }
        throw new AssertionError("callback not found: " + name);
    }

    private Map<String, Object> args(String key, String value) {
        Map<String, Object> map = new HashMap<String, Object>();
        if (value != null) map.put(key, value);
        return map;
    }

    // ---- Index tests ----

    // AC5: findByName
    @Test
    void findByName_exactMatch() {
        DomainKnowledge dk = index.findByName("调拨计划");
        assertThat(dk).isNotNull();
        assertThat(dk.getName()).isEqualTo("调拨计划");
        assertThat(dk.getTables()).contains("drp_allocation_plan");
    }

    @Test
    void findByName_notFound() {
        assertThat(index.findByName("不存在的概念")).isNull();
        assertThat(index.findByName(null)).isNull();
        assertThat(index.findByName("")).isNull();
    }

    // AC6: findByTable
    @Test
    void findByTable_shouldReturnConcepts() {
        List<DomainKnowledge> results = index.findByTable("drp_allocation_plan");
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getName()).isEqualTo("调拨计划");
    }

    @Test
    void findByTable_caseInsensitive() {
        List<DomainKnowledge> results = index.findByTable("DRP_ALLOCATION_PLAN");
        assertThat(results).hasSize(1);
    }

    @Test
    void findByTable_notFound() {
        assertThat(index.findByTable("nonexistent_table")).isEmpty();
        assertThat(index.findByTable(null)).isEmpty();
    }

    // AC7: findByService
    @Test
    void findByService_shouldReturnConcepts() {
        List<DomainKnowledge> results = index.findByService("AllocationPlanService");
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getName()).isEqualTo("调拨计划");
    }

    @Test
    void findByService_caseInsensitive() {
        List<DomainKnowledge> results = index.findByService("allocationplanservice");
        assertThat(results).hasSize(1);
    }

    // AC8: findAll
    @Test
    void findAll_shouldReturnAll() {
        assertThat(index.findAll()).hasSize(3);
        assertThat(index.size()).isEqualTo(3);
    }

    @Test
    void clear_shouldRemoveAll() {
        index.clear();
        assertThat(index.size()).isEqualTo(0);
        assertThat(index.findAll()).isEmpty();
    }

    // ---- Tools tests ----

    @Test
    void shouldReflectFourToolMethods() {
        assertThat(callbacks).hasSize(4);
        assertThat(findByName("lookup_concept")).isNotNull();
        assertThat(findByName("find_by_table")).isNotNull();
        assertThat(findByName("find_by_class")).isNotNull();
        assertThat(findByName("list_concepts")).isNotNull();
    }

    @Test
    void eachCallbackShouldHaveNonEmptyJsonSchema() {
        for (ToolCallback cb : callbacks) {
            assertThat(cb.getJsonSchema()).isNotEmpty();
            assertThat(cb.getDescription()).isNotEmpty();
        }
    }

    // AC9: lookup_concept
    @Test
    void lookupConcept_found() {
        ToolResult result = findByName("lookup_concept").execute(args("conceptName", "调拨计划"), null);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("概念: 调拨计划");
        assertThat(result.getContent()).contains("drp_allocation_plan");
        assertThat(result.getContent()).contains("AllocationPlanService");
    }

    // AC12: lookup_concept not found
    @Test
    void lookupConcept_notFound() {
        ToolResult result = findByName("lookup_concept").execute(args("conceptName", "不存在"), null);
        assertThat(result.getContent()).contains("未找到概念");
    }

    // AC10: find_by_table
    @Test
    void findByTable_tool() {
        ToolResult result = findByName("find_by_table").execute(args("tableName", "drp_allocation_plan"), null);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("调拨计划");
        assertThat(result.getContent()).contains("1 个");
    }

    @Test
    void findByTable_tool_notFound() {
        ToolResult result = findByName("find_by_table").execute(args("tableName", "nonexistent"), null);
        assertThat(result.getContent()).contains("未找到涉及表");
    }

    // AC11: list_concepts
    @Test
    void listConcepts_tool() {
        ToolResult result = findByName("list_concepts").execute(new HashMap<String, Object>(), null);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("3 个");
        assertThat(result.getContent()).contains("调拨计划");
        assertThat(result.getContent()).contains("补货策略");
        assertThat(result.getContent()).contains("安全库存");
    }

    // find_by_class
    @Test
    void findByClass_tool() {
        ToolResult result = findByName("find_by_class").execute(args("className", "AllocationPlanService"), null);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("调拨计划");
    }

    // Null/empty handling
    @Test
    void lookupConcept_nullInput() {
        ToolResult result = findByName("lookup_concept").execute(args("conceptName", null), null);
        assertThat(result.getContent()).contains("请提供概念名称");
    }

    @Test
    void lookupConcept_emptyInput() {
        ToolResult result = findByName("lookup_concept").execute(args("conceptName", ""), null);
        assertThat(result.getContent()).contains("请提供概念名称");
    }
}
