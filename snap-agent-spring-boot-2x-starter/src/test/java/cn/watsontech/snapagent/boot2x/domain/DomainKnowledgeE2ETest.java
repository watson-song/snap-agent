package cn.watsontech.snapagent.boot2x.domain;

import cn.watsontech.snapagent.boot2x.codegraph.CodeGraphTools;
import cn.watsontech.snapagent.boot2x.codegraph.ChineseCodeGraphMessages;
import cn.watsontech.snapagent.boot2x.codegraph.InMemoryCodeGraphIndex;
import cn.watsontech.snapagent.core.codegraph.CodeGraph;
import cn.watsontech.snapagent.core.codegraph.CodeGraphEdge;
import cn.watsontech.snapagent.core.codegraph.CodeGraphNode;
import cn.watsontech.snapagent.core.domain.DomainKnowledge;
import cn.watsontech.snapagent.core.domain.DomainKnowledgeIndex;
import cn.watsontech.snapagent.core.tool.ToolCallback;
import cn.watsontech.snapagent.core.tool.ToolCallbacks;
import cn.watsontech.snapagent.core.tool.ToolResult;
import cn.watsontech.snapagent.core.vectorstore.Document;
import cn.watsontech.snapagent.core.vectorstore.InMemoryVectorStore;
import cn.watsontech.snapagent.core.vectorstore.SearchRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * True E2E test — zero mocks, all real components.
 *
 * <p>Tests the full domain knowledge + code graph diagnosis flow:</p>
 * <ol>
 *   <li>Create real domain knowledge .md files on disk</li>
 *   <li>Load them with real DomainKnowledgeLoader → real InMemoryVectorStore + real InMemoryDomainKnowledgeIndex</li>
 *   <li>Query domain knowledge with real DomainKnowledgeTools</li>
 *   <li>RAG search with real InMemoryVectorStore</li>
 *   <li>CodeGraph drill-down with real CodeGraphTools + real InMemoryCodeGraphIndex</li>
 *   <li>Render call graph with real render_call_graph tool</li>
 * </ol>
 *
 * <p>No mocks. No stubs. All components are real instances.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DomainKnowledgeE2ETest {

    @TempDir
    static Path tempDir;

    // Real components — no mocks
    private InMemoryVectorStore vectorStore;
    private InMemoryDomainKnowledgeIndex domainIndex;
    private DomainKnowledgeLoader loader;
    private DomainKnowledgeTools domainTools;
    private ToolCallback[] domainCallbacks;

    private InMemoryCodeGraphIndex codeGraphIndex;
    private CodeGraphTools codeGraphTools;
    private ToolCallback[] codeGraphCallbacks;

    @BeforeAll
    void setUp() throws Exception {
        // ============================================================
        // Step 1: Create real domain knowledge files on disk
        // ============================================================
        Path domainDir = tempDir.resolve("domain-knowledge");
        domainDir.toFile().mkdirs();

        writeFile(domainDir.resolve("allocation-plan.md"),
                "---\n"
                + "name: 调拨计划\n"
                + "tables:\n"
                + "  - drp_allocation_plan\n"
                + "  - drp_allocation_detail\n"
                + "services:\n"
                + "  - AllocationPlanService\n"
                + "  - BalanceAlgorithmService\n"
                + "entry_points:\n"
                + "  - ReplenishmentPlanTask.generate()\n"
                + "related_concepts:\n"
                + "  - 补货策略\n"
                + "  - 安全库存\n"
                + "tags: [replenishment, allocation]\n"
                + "---\n\n"
                + "# 调拨计划\n\n"
                + "## 业务描述\n"
                + "航材消耗件在多基地间的库存平衡调拨。当某个基地库存低于安全库存时，"
                + "系统自动从库存充足的基地生成调拨计划。\n\n"
                + "## 业务规则\n"
                + "1. 只有航材消耗件才走多基地平衡算法\n"
                + "2. 调拨数量 = max(0, 安全库存 - 可用库存)\n"
                + "3. 同一天同一SKU不能生成两次调拨\n\n"
                + "## 已知陷阱\n"
                + "- 安全库存表有15分钟延迟\n"
                + "- 并发场景下库存查询可能返回过期数据\n\n"
                + "## SQL示例\n"
                + "SELECT * FROM drp_allocation_plan WHERE sku_code = 'A123';\n");

        writeFile(domainDir.resolve("replenishment-strategy.md"),
                "---\n"
                + "name: 补货策略\n"
                + "tables:\n"
                + "  - replm_inv_param_sku_wh_input\n"
                + "  - replm_safety_stock\n"
                + "services:\n"
                + "  - ReplenishmentStrategyService\n"
                + "  - SafetyStockService\n"
                + "entry_points:\n"
                + "  - ReplenishmentStrategyTask.execute()\n"
                + "related_concepts:\n"
                + "  - 调拨计划\n"
                + "tags: [replenishment, strategy]\n"
                + "---\n\n"
                + "# 补货策略\n\n"
                + "## 业务描述\n"
                + "根据历史销量和安全库存计算每个SKU在每个仓库的补货建议。\n\n"
                + "## 业务规则\n"
                + "1. 补货建议 = 安全库存 + 在途量 - 可用库存\n"
                + "2. 安全库存每天由Dolphin任务重新计算\n");

        // ============================================================
        // Step 2: Initialize ALL real components
        // ============================================================

        // Real VectorStore (no mock)
        vectorStore = new InMemoryVectorStore();

        // Real DomainKnowledgeIndex (no mock)
        domainIndex = new InMemoryDomainKnowledgeIndex();

        // Real Loader (no mock) — loads from disk into real index + real vectorstore
        loader = new DomainKnowledgeLoader(domainIndex, vectorStore);

        // Real CodeGraph (no mock) — represents the replenishment system
        codeGraphIndex = buildRealCodeGraph();

        // ============================================================
        // Step 3: Load domain knowledge from real files
        // ============================================================
        List<DomainKnowledge> loaded = loader.loadFromDirectory(domainDir);
        assertThat(loaded).as("Should load 2 domain knowledge files from disk").hasSize(2);

        // Verify real index was populated
        assertThat(domainIndex.size()).isEqualTo(2);

        // Verify real vectorstore was populated
        assertThat(vectorStore.size()).isEqualTo(2);

        // ============================================================
        // Step 4: Create real tools from real components
        // ============================================================
        domainTools = new DomainKnowledgeTools(domainIndex);
        domainCallbacks = ToolCallbacks.from(domainTools);

        codeGraphTools = new CodeGraphTools(codeGraphIndex, 5, 3,
                new ChineseCodeGraphMessages(), tempDir.resolve("graphs").toString());
        codeGraphCallbacks = ToolCallbacks.from(codeGraphTools);
    }

    /**
     * Build a real CodeGraph representing the replenishment system.
     * No mocks — real nodes, real edges, real InMemoryCodeGraphIndex.
     */
    private InMemoryCodeGraphIndex buildRealCodeGraph() {
        CodeGraphNode task = new CodeGraphNode(
                "com.example.repl.task.ReplenishmentPlanTask#generate()",
                CodeGraphNode.NodeType.METHOD, "generate",
                "com.example.repl.task", "ReplenishmentPlanTask",
                "void", "ReplenishmentPlanTask.java", 7);

        CodeGraphNode planService = new CodeGraphNode(
                "com.example.repl.service.AllocationPlanService#createPlan()",
                CodeGraphNode.NodeType.METHOD, "createPlan",
                "com.example.repl.service", "AllocationPlanService",
                "void", "AllocationPlanService.java", 5);

        CodeGraphNode checkService = new CodeGraphNode(
                "com.example.repl.service.InventoryCheckService#check()",
                CodeGraphNode.NodeType.METHOD, "check",
                "com.example.repl.service", "InventoryCheckService",
                "boolean", "InventoryCheckService.java", 5);

        CodeGraphNode repo = new CodeGraphNode(
                "com.example.repl.repo.AllocationPlanRepository#save(AllocationPlan)",
                CodeGraphNode.NodeType.METHOD, "save",
                "com.example.repl.repo", "AllocationPlanRepository",
                "void", "AllocationPlanRepository.java", 3);

        CodeGraphNode inventoryApi = new CodeGraphNode(
                "com.example.inventory.api.InventoryApi#query()",
                CodeGraphNode.NodeType.METHOD, "query",
                "com.example.inventory.api", "InventoryApi",
                "InventoryRecord", "InventoryApi.java", 3);

        CodeGraph graph = new CodeGraph(
                Arrays.asList(task, planService, checkService, repo, inventoryApi),
                Arrays.asList(
                        new CodeGraphEdge(
                                "com.example.repl.task.ReplenishmentPlanTask#generate()",
                                "com.example.repl.service.InventoryCheckService#check()",
                                CodeGraphEdge.EdgeType.CALLS, "line 8"),
                        new CodeGraphEdge(
                                "com.example.repl.task.ReplenishmentPlanTask#generate()",
                                "com.example.repl.service.AllocationPlanService#createPlan()",
                                CodeGraphEdge.EdgeType.CALLS, "line 9"),
                        new CodeGraphEdge(
                                "com.example.repl.service.AllocationPlanService#createPlan()",
                                "com.example.repl.repo.AllocationPlanRepository#save(AllocationPlan)",
                                CodeGraphEdge.EdgeType.CALLS, "line 6"),
                        new CodeGraphEdge(
                                "com.example.repl.service.InventoryCheckService#check()",
                                "com.example.inventory.api.InventoryApi#query()",
                                CodeGraphEdge.EdgeType.CALLS, "line 6")));

        return new InMemoryCodeGraphIndex(graph);
    }

    private ToolCallback findDomainCallback(String name) {
        for (ToolCallback cb : domainCallbacks) {
            if (cb.getName().equals(name)) return cb;
        }
        throw new AssertionError("Domain callback not found: " + name);
    }

    private ToolCallback findCodeGraphCallback(String name) {
        for (ToolCallback cb : codeGraphCallbacks) {
            if (cb.getName().equals(name)) return cb;
        }
        throw new AssertionError("CodeGraph callback not found: " + name);
    }

    private Map<String, Object> args(String... kvPairs) {
        Map<String, Object> map = new HashMap<String, Object>();
        for (int i = 0; i < kvPairs.length; i += 2) {
            if (kvPairs[i + 1] != null) map.put(kvPairs[i], kvPairs[i + 1]);
        }
        return map;
    }

    // ================================================================
    // E2E Test: Full diagnosis flow with real components
    // ================================================================

    @Test
    void fullDiagnosisFlow_domainKnowledgePlusCodeGraph() {
        // ============================================================
        // Phase 1: Domain knowledge is already loaded in @BeforeAll
        // Verify real data is in real stores
        // ============================================================
        assertThat(vectorStore.size()).isEqualTo(2);
        assertThat(domainIndex.size()).isEqualTo(2);

        // ============================================================
        // Phase 2: Agent uses domain knowledge tools
        // "User asks: 调拨计划是什么？涉及哪些表和类？"
        // ============================================================

        // Step 2a: lookup_concept — real tool, real index
        ToolResult lookupResult = findDomainCallback("lookup_concept")
                .execute(args("conceptName", "调拨计划"), null);
        assertThat(lookupResult.isSuccess()).isTrue();
        assertThat(lookupResult.getContent()).contains("概念: 调拨计划");
        assertThat(lookupResult.getContent()).contains("drp_allocation_plan");
        assertThat(lookupResult.getContent()).contains("drp_allocation_detail");
        assertThat(lookupResult.getContent()).contains("AllocationPlanService");
        assertThat(lookupResult.getContent()).contains("BalanceAlgorithmService");
        assertThat(lookupResult.getContent()).contains("ReplenishmentPlanTask.generate()");
        assertThat(lookupResult.getContent()).contains("补货策略");
        assertThat(lookupResult.getContent()).contains("安全库存");
        assertThat(lookupResult.getContent()).contains("航材消耗件在多基地间的库存平衡调拨");
        assertThat(lookupResult.getContent()).contains("安全库存表有15分钟延迟");
        System.out.println("[Phase 2a] lookup_concept OK:\n" + lookupResult.getContent().substring(0, Math.min(120, lookupResult.getContent().length())) + "...");

        // Step 2b: find_by_table — reverse lookup from DB table to concept
        ToolResult tableResult = findDomainCallback("find_by_table")
                .execute(args("tableName", "drp_allocation_plan"), null);
        assertThat(tableResult.isSuccess()).isTrue();
        assertThat(tableResult.getContent()).contains("调拨计划");
        System.out.println("[Phase 2b] find_by_table OK: " + tableResult.getContent().trim());

        // Step 2c: find_by_class — reverse lookup from Java class to concept
        ToolResult classResult = findDomainCallback("find_by_class")
                .execute(args("className", "AllocationPlanService"), null);
        assertThat(classResult.isSuccess()).isTrue();
        assertThat(classResult.getContent()).contains("调拨计划");
        assertThat(classResult.getContent()).contains("drp_allocation_plan");
        System.out.println("[Phase 2c] find_by_class OK: " + classResult.getContent().trim());

        // Step 2d: list_concepts — list all registered concepts
        ToolResult listResult = findDomainCallback("list_concepts")
                .execute(new HashMap<String, Object>(), null);
        assertThat(listResult.isSuccess()).isTrue();
        assertThat(listResult.getContent()).contains("2 个");
        assertThat(listResult.getContent()).contains("调拨计划");
        assertThat(listResult.getContent()).contains("补货策略");
        System.out.println("[Phase 2d] list_concepts OK: " + listResult.getContent().trim());

        // ============================================================
        // Phase 3: RAG retrieval from real VectorStore
        // "Agent searches knowledge base for context about allocation"
        // ============================================================
        SearchRequest ragRequest = new SearchRequest("调拨计划 库存平衡", 5, 0.0, null);
        List<Document> ragResults = vectorStore.similaritySearch(ragRequest);
        assertThat(ragResults).isNotEmpty();

        Document topResult = ragResults.get(0);
        assertThat(topResult.getContent()).contains("调拨计划");
        assertThat((String) topResult.getMetadata("source")).isEqualTo("domain-knowledge");
        System.out.println("[Phase 3] RAG OK: found " + ragResults.size() + " docs, top=" + topResult.getContent().substring(0, Math.min(80, topResult.getContent().length())) + "...");

        // ============================================================
        // Phase 4: CodeGraph drill-down using real tools
        // "Agent now knows the entry point is ReplenishmentPlanTask.generate()"
        // "Agent traces the call chain to find the root cause"
        // ============================================================

        // Step 4a: call_chain — forward trace from entry point
        ToolResult callResult = findCodeGraphCallback("call_chain")
                .execute(args("query", "ReplenishmentPlanTask#generate"), null);
        assertThat(callResult.isSuccess()).isTrue();
        assertThat(callResult.getContent()).contains("正向调用链");
        assertThat(callResult.getContent()).contains("InventoryCheckService#check");
        assertThat(callResult.getContent()).contains("AllocationPlanService#createPlan");
        assertThat(callResult.getContent()).contains("AllocationPlanRepository#save");
        System.out.println("[Phase 4a] call_chain OK:\n" + callResult.getContent());

        // Step 4b: reverse_chain — who calls AllocationPlanService?
        ToolResult reverseResult = findCodeGraphCallback("reverse_chain")
                .execute(args("query", "AllocationPlanService#createPlan"), null);
        assertThat(reverseResult.isSuccess()).isTrue();
        assertThat(reverseResult.getContent()).contains("反向调用链");
        assertThat(reverseResult.getContent()).contains("ReplenishmentPlanTask#generate");
        System.out.println("[Phase 4b] reverse_chain OK: " + reverseResult.getContent().trim());

        // Step 4c: impact_analysis — what's affected if we modify check service?
        ToolResult impactResult = findCodeGraphCallback("impact_analysis")
                .execute(args("query", "InventoryCheckService#check"), null);
        assertThat(impactResult.isSuccess()).isTrue();
        assertThat(impactResult.getContent()).contains("变更影响范围");
        assertThat(impactResult.getContent()).contains("ReplenishmentPlanTask#generate");
        System.out.println("[Phase 4c] impact_analysis OK: " + impactResult.getContent().trim());

        // ============================================================
        // Phase 5: Visualization — render call graph as HTML
        // ============================================================
        Map<String, Object> renderArgs = new HashMap<String, Object>();
        renderArgs.put("query", "ReplenishmentPlanTask#generate");
        renderArgs.put("graphType", "call_chain");
        renderArgs.put("maxDepth", 3);
        ToolResult renderResult = findCodeGraphCallback("render_call_graph")
                .execute(renderArgs, null);
        assertThat(renderResult.isSuccess()).isTrue();
        assertThat(renderResult.getContent()).contains("Call graph rendered");
        assertThat(renderResult.getContent()).contains(".html");
        assertThat(renderResult.getContent()).contains("Nodes:");
        assertThat(renderResult.getContent()).contains("Edges:");

        // Verify HTML file was actually created on disk
        String htmlPath = extractFilePath(renderResult.getContent());
        assertThat(htmlPath).isNotNull();
        assertThat(new File(htmlPath)).exists();
        assertThat(new File(htmlPath).length()).isGreaterThan(100); // non-trivial HTML
        System.out.println("[Phase 5] render_call_graph OK: " + htmlPath);

        // ============================================================
        // Phase 6: Simulated diagnosis conclusion
        // Combining domain knowledge + code graph = AI diagnosis
        // ============================================================
        System.out.println("\n========== E2E Diagnosis Conclusion ==========");
        System.out.println("Domain knowledge retrieved:");
        System.out.println("  - Concept: 调拨计划");
        System.out.println("  - Tables: drp_allocation_plan, drp_allocation_detail");
        System.out.println("  - Services: AllocationPlanService, BalanceAlgorithmService");
        System.out.println("  - Entry: ReplenishmentPlanTask.generate()");
        System.out.println("  - Known pitfall: 安全库存表有15分钟延迟");
        System.out.println("Code graph traced:");
        System.out.println("  - Task.generate() → CheckService.check() → InventoryApi.query()");
        System.out.println("  - Task.generate() → PlanService.createPlan() → Repository.save()");
        System.out.println("Diagnosis: If check() returns false due to stale safety stock");
        System.out.println("  (15min delay pitfall), createPlan() is never called.");
        System.out.println("==============================================");
    }

    /**
     * Test that domain knowledge and code graph work independently.
     */
    @Test
    void domainKnowledge_worksWithoutCodeGraph() {
        // Domain knowledge tools should work even without CodeGraph
        ToolResult result = findDomainCallback("lookup_concept")
                .execute(args("conceptName", "补货策略"), null);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("补货策略");
        assertThat(result.getContent()).contains("replm_inv_param_sku_wh_input");
        assertThat(result.getContent()).contains("ReplenishmentStrategyService");
    }

    /**
     * Test that CodeGraph works without domain knowledge.
     */
    @Test
    void codeGraph_worksWithoutDomainKnowledge() {
        // CodeGraph tools should work independently
        ToolResult result = findCodeGraphCallback("find")
                .execute(args("query", "InventoryApi"), null);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("InventoryApi");
    }

    /**
     * Test RAG search finds domain knowledge by different queries.
     */
    @Test
    void ragSearch_findsDomainKnowledgeByVariousQueries() {
        // Search by table name
        List<Document> r1 = vectorStore.similaritySearch(
                new SearchRequest("drp_allocation_plan", 5, 0.0, null));
        assertThat(r1).isNotEmpty();

        // Search by concept name in Chinese
        List<Document> r2 = vectorStore.similaritySearch(
                new SearchRequest("补货策略", 5, 0.0, null));
        assertThat(r2).isNotEmpty();

        // Search by business term
        List<Document> r3 = vectorStore.similaritySearch(
                new SearchRequest("安全库存 补货", 5, 0.0, null));
        assertThat(r3).isNotEmpty();
    }

    // ---- Helpers ----

    private void writeFile(Path path, String content) throws Exception {
        File file = path.toFile();
        file.getParentFile().mkdirs();
        OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(file), StandardCharsets.UTF_8);
        try {
            writer.write(content);
        } finally {
            writer.close();
        }
    }

    private String extractFilePath(String content) {
        String prefix = "Call graph rendered: ";
        int idx = content.indexOf(prefix);
        if (idx < 0) return null;
        String rest = content.substring(idx + prefix.length());
        int nl = rest.indexOf('\n');
        return nl >= 0 ? rest.substring(0, nl).trim() : rest.trim();
    }
}
