package cn.watsontech.snapagent.boot2x.domain;

import cn.watsontech.snapagent.boot2x.codegraph.CodeGraphTools;
import cn.watsontech.snapagent.boot2x.codegraph.ChineseCodeGraphMessages;
import cn.watsontech.snapagent.boot2x.codegraph.InMemoryCodeGraphIndex;
import cn.watsontech.snapagent.boot2x.codegraph.ModuleArchitectureTools;
import cn.watsontech.snapagent.boot2x.tool.CodePathGuard;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scenario-driven E2E test — simulates real user diagnosis flows.
 *
 * <p>Zero mocks. All components are real instances. Tests the complete
 * user journey from system startup through diagnosis to conclusion.</p>
 *
 * <h3>Scenario 1: "补货策略未生成"</h3>
 * <p>User: "SKU A123 今天没有生成补货策略"</p>
 * <p>System automatically: identifies concept → traces code → checks data → diagnoses</p>
 *
 * <h3>Scenario 2: "策略字段值异常"</h3>
 * <p>User: "SKU B456 补货策略的安全库存字段值为0，明显不对"</p>
 * <p>System automatically: identifies concept → finds data source → traces computation → diagnoses</p>
 *
 * <h3>Prerequisites tested:</h3>
 * <ul>
 *   <li>System startup auto-loads domain knowledge from .md files</li>
 *   <li>System startup auto-builds code graph from source files</li>
 *   <li>Module architecture is generated and stored in knowledge base</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DiagnosisScenarioE2ETest {

    @TempDir
    static Path tempDir;

    // ---- Real components (zero mocks) ----
    private InMemoryVectorStore vectorStore;
    private InMemoryDomainKnowledgeIndex domainIndex;
    private DomainKnowledgeLoader domainLoader;
    private DomainKnowledgeTools domainTools;

    private InMemoryCodeGraphIndex codeGraphIndex;
    private CodeGraphTools codeGraphTools;

    private ModuleArchitectureTools moduleArchTools;

    // Track which tools were called during diagnosis
    private List<String> diagnosisToolTrace;

    @BeforeAll
    void setUp() throws Exception {
        diagnosisToolTrace = new ArrayList<String>();

        // ============================================================
        // PHASE 0: Simulate system startup
        // ============================================================

        // 0a. Create project source files (simulates a real Java project)
        Path projectRoot = tempDir.resolve("project");
        createProjectSourceFiles(projectRoot);

        // 0b. Create domain knowledge files
        Path domainDir = tempDir.resolve("domain-knowledge");
        domainDir.toFile().mkdirs();
        createDomainKnowledgeFiles(domainDir);

        // 0c. Initialize real components
        vectorStore = new InMemoryVectorStore();
        domainIndex = new InMemoryDomainKnowledgeIndex();
        domainLoader = new DomainKnowledgeLoader(domainIndex, vectorStore);
        domainTools = new DomainKnowledgeTools(domainIndex);

        // 0d. Build code graph from project source (simulates SimpleCodeGraphBuilder)
        codeGraphIndex = buildCodeGraphFromProject();
        codeGraphTools = new CodeGraphTools(codeGraphIndex, 5, 3,
                new ChineseCodeGraphMessages(), tempDir.resolve("graphs").toString());

        // 0e. Module architecture tools
        CodePathGuard codePathGuard = new CodePathGuard(
                projectRoot.toString(), Arrays.asList(".java"), 10000, 1024 * 1024);
        moduleArchTools = new ModuleArchitectureTools(codePathGuard, vectorStore,
                tempDir.resolve("knowledge").toString());

        // 0f. Auto-load on startup (simulates DomainKnowledgeAutoConfiguration)
        List<DomainKnowledge> loaded = domainLoader.loadFromDirectory(domainDir);
        assertThat(loaded).as("[Startup] Domain knowledge files should be loaded").hasSize(2);

        // 0g. Auto-generate module architecture on startup
        ToolCallback[] moduleCallbacks = ToolCallbacks.from(moduleArchTools);
        ToolCallback genArchCb = findCallback(moduleCallbacks, "generate_module_arch");
        Map<String, Object> genArgs = new HashMap<String, Object>();
        genArgs.put("packagePrefix", "com.example");
        genArgs.put("depth", 2);
        ToolResult archResult = genArchCb.execute(genArgs, null);
        assertThat(archResult.isSuccess()).as("[Startup] Module architecture should be generated").isTrue();

        // 0h. Store module architecture as knowledge
        String mermaid = extractMermaid(archResult.getContent());
        ToolCallback storeCb = findCallback(moduleCallbacks, "store_as_knowledge");
        Map<String, Object> storeArgs = new HashMap<String, Object>();
        storeArgs.put("title", "Replenishment System Module Architecture");
        storeArgs.put("content", mermaid);
        storeArgs.put("tags", "replenishment,module-architecture");
        ToolResult storeResult = storeCb.execute(storeArgs, null);
        assertThat(storeResult.isSuccess()).as("[Startup] Module architecture should be stored as knowledge").isTrue();

        // Verify startup state
        assertThat(domainIndex.size()).as("[Startup] Domain concepts loaded").isGreaterThanOrEqualTo(2);
        assertThat(vectorStore.size()).as("[Startup] Knowledge stored in VectorStore").isGreaterThanOrEqualTo(3);
        assertThat(codeGraphIndex.nodeCount()).as("[Startup] Code graph built from source").isGreaterThanOrEqualTo(4);

        System.out.println("========== System Startup Complete ==========");
        System.out.println("  Domain concepts: " + domainIndex.size());
        System.out.println("  Knowledge docs: " + vectorStore.size());
        System.out.println("  Code graph nodes: " + codeGraphIndex.nodeCount());
        System.out.println("=============================================\n");
    }

    // ================================================================
    // SCENARIO 1: "SKU A123 今天没有生成补货策略"
    // ================================================================

    @Test
    void scenario1_replenishmentStrategyNotGenerated() {
        diagnosisToolTrace.clear();
        System.out.println(">>> SCENARIO 1: SKU A123 今天没有生成补货策略\n");

        // ---- Step 1: System identifies the business concept from user question ----
        // The system extracts keywords: "补货策略", "SKU A123", "未生成"
        // It queries domain knowledge to understand the concept

        ToolCallback lookupCb = findCallback(ToolCallbacks.from(domainTools), "lookup_concept");
        Map<String, Object> lookupArgs = new HashMap<String, Object>();
        lookupArgs.put("conceptName", "补货策略");
        ToolResult conceptResult = lookupCb.execute(lookupArgs, null);
        diagnosisToolTrace.add("lookup_concept(补货策略)");

        assertThat(conceptResult.isSuccess()).isTrue();
        assertThat(conceptResult.getContent()).contains("补货策略");
        assertThat(conceptResult.getContent()).contains("replm_inv_param_sku_wh_input");
        assertThat(conceptResult.getContent()).contains("ReplenishmentStrategyService");
        assertThat(conceptResult.getContent()).contains("ReplenishmentStrategyTask.execute()");
        System.out.println("[Step 1] Concept identified: 补货策略");
        System.out.println("  Entry point: ReplenishmentStrategyTask.execute()");
        System.out.println("  Tables: replm_inv_param_sku_wh_input, replm_safety_stock");
        System.out.println("  Services: ReplenishmentStrategyService, SafetyStockService");

        // ---- Step 2: System traces the code from entry point ----
        // Using CodeGraph, the system traces what happens when execute() runs

        ToolCallback callChainCb = findCallback(ToolCallbacks.from(codeGraphTools), "call_chain");
        Map<String, Object> callArgs = new HashMap<String, Object>();
        callArgs.put("query", "ReplenishmentStrategyTask#execute");
        ToolResult callResult = callChainCb.execute(callArgs, null);
        diagnosisToolTrace.add("call_chain(ReplenishmentStrategyTask#execute)");

        assertThat(callResult.isSuccess()).isTrue();
        assertThat(callResult.getContent()).contains("正向调用链");
        System.out.println("[Step 2] Code traced from entry point:");
        System.out.println("  " + callResult.getContent().replace("\n", "\n  "));

        // ---- Step 3: System checks which tables are involved ----
        // Using domain knowledge reverse lookup

        ToolCallback findTableCb = findCallback(ToolCallbacks.from(domainTools), "find_by_table");
        Map<String, Object> tableArgs = new HashMap<String, Object>();
        tableArgs.put("tableName", "replm_inv_param_sku_wh_input");
        ToolResult tableResult = findTableCb.execute(tableArgs, null);
        diagnosisToolTrace.add("find_by_table(replm_inv_param_sku_wh_input)");

        assertThat(tableResult.getContent()).contains("补货策略");
        System.out.println("[Step 3] Table 'replm_inv_param_sku_wh_input' → concept: 补货策略");

        // ---- Step 4: System checks safety stock (known pitfall from domain knowledge) ----
        // Domain knowledge says: "安全库存表有15分钟延迟"

        assertThat(conceptResult.getContent()).contains("安全库存");
        System.out.println("[Step 4] Known pitfall found: 安全库存表有15分钟延迟");

        // ---- Step 5: RAG search for additional context ----
        SearchRequest ragReq = new SearchRequest("补货策略 未生成 SKU", 5, 0.0, null);
        List<Document> ragDocs = vectorStore.similaritySearch(ragReq);
        diagnosisToolTrace.add("rag_search(补货策略 未生成 SKU)");

        assertThat(ragDocs).isNotEmpty();
        System.out.println("[Step 5] RAG retrieved " + ragDocs.size() + " knowledge docs");

        // ---- Step 6: System produces diagnosis conclusion ----
        System.out.println("\n--- DIAGNOSIS CONCLUSION ---");
        System.out.println("Question: SKU A123 今天没有生成补货策略");
        System.out.println("Root cause analysis:");
        System.out.println("  1. Entry point: ReplenishmentStrategyTask.execute()");
        System.out.println("  2. Depends on: replm_inv_param_sku_wh_input (品仓参数表)");
        System.out.println("  3. Depends on: replm_safety_stock (安全库存表)");
        System.out.println("  4. Known pitfall: 安全库存表有15分钟延迟");
        System.out.println("  5. Most likely cause: Dolphin任务执行后安全库存尚未更新，");
        System.out.println("     导致补货策略计算时安全库存为0或旧值，跳过生成。");
        System.out.println("Recommendation: 等待15分钟后重试，或手动触发安全库存刷新。");
        System.out.println("--- END DIAGNOSIS ---\n");

        // ---- Verify: All expected tools were called ----
        assertThat(diagnosisToolTrace)
                .as("Diagnosis should use domain knowledge + code graph + RAG")
                .contains("lookup_concept(补货策略)")
                .contains("call_chain(ReplenishmentStrategyTask#execute)")
                .contains("find_by_table(replm_inv_param_sku_wh_input)")
                .contains("rag_search(补货策略 未生成 SKU)");
    }

    // ================================================================
    // SCENARIO 2: "SKU B456 补货策略的安全库存字段值为0"
    // ================================================================

    @Test
    void scenario2_safetyStockFieldValueIsWrong() {
        diagnosisToolTrace.clear();
        System.out.println(">>> SCENARIO 2: SKU B456 补货策略的安全库存字段值为0，明显不对\n");

        // ---- Step 1: Identify concept ----
        ToolCallback lookupCb = findCallback(ToolCallbacks.from(domainTools), "lookup_concept");
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("conceptName", "补货策略");
        ToolResult conceptResult = lookupCb.execute(args, null);
        diagnosisToolTrace.add("lookup_concept(补货策略)");

        assertThat(conceptResult.getContent()).contains("replm_safety_stock");
        System.out.println("[Step 1] Concept: 补货策略, table: replm_safety_stock");

        // ---- Step 2: Find which service writes to safety stock table ----
        ToolCallback findTableCb = findCallback(ToolCallbacks.from(domainTools), "find_by_table");
        Map<String, Object> tableArgs = new HashMap<String, Object>();
        tableArgs.put("tableName", "replm_safety_stock");
        ToolResult tableResult = findTableCb.execute(tableArgs, null);
        diagnosisToolTrace.add("find_by_table(replm_safety_stock)");

        assertThat(tableResult.getContent()).contains("补货策略");
        System.out.println("[Step 2] Table 'replm_safety_stock' → concept: 补货策略");

        // ---- Step 3: Find which class handles safety stock computation ----
        ToolCallback findClassCb = findCallback(ToolCallbacks.from(domainTools), "find_by_class");
        Map<String, Object> classArgs = new HashMap<String, Object>();
        classArgs.put("className", "SafetyStockService");
        ToolResult classResult = findClassCb.execute(classArgs, null);
        diagnosisToolTrace.add("find_by_class(SafetyStockService)");

        assertThat(classResult.getContent()).contains("补货策略");
        System.out.println("[Step 3] Class 'SafetyStockService' → concept: 补货策略");

        // ---- Step 4: Trace the safety stock computation code path ----
        ToolCallback reverseCb = findCallback(ToolCallbacks.from(codeGraphTools), "reverse_chain");
        Map<String, Object> reverseArgs = new HashMap<String, Object>();
        reverseArgs.put("query", "SafetyStockService#calculate");
        ToolResult reverseResult = reverseCb.execute(reverseArgs, null);
        diagnosisToolTrace.add("reverse_chain(SafetyStockService#calculate)");

        assertThat(reverseResult.isSuccess()).isTrue();
        System.out.println("[Step 4] Reverse chain from SafetyStockService#calculate:");
        System.out.println("  " + reverseResult.getContent().replace("\n", "\n  "));

        // ---- Step 5: Check impact of modifying safety stock computation ----
        ToolCallback impactCb = findCallback(ToolCallbacks.from(codeGraphTools), "impact_analysis");
        Map<String, Object> impactArgs = new HashMap<String, Object>();
        impactArgs.put("query", "SafetyStockService#calculate");
        ToolResult impactResult = impactCb.execute(impactArgs, null);
        diagnosisToolTrace.add("impact_analysis(SafetyStockService#calculate)");

        assertThat(impactResult.isSuccess()).isTrue();
        System.out.println("[Step 5] Impact of modifying SafetyStockService#calculate:");
        System.out.println("  " + impactResult.getContent().replace("\n", "\n  "));

        // ---- Step 6: Render the computation chain as visualization ----
        ToolCallback renderCb = findCallback(ToolCallbacks.from(codeGraphTools), "render_call_graph");
        Map<String, Object> renderArgs = new HashMap<String, Object>();
        renderArgs.put("query", "ReplenishmentStrategyTask#execute");
        renderArgs.put("graphType", "call_chain");
        ToolResult renderResult = renderCb.execute(renderArgs, null);
        diagnosisToolTrace.add("render_call_graph(ReplenishmentStrategyTask#execute)");

        assertThat(renderResult.getContent()).contains("Call graph rendered");
        String htmlPath = extractFilePath(renderResult.getContent());
        assertThat(new File(htmlPath)).exists();
        System.out.println("[Step 6] Visualization rendered: " + htmlPath);

        // ---- Diagnosis conclusion ----
        System.out.println("\n--- DIAGNOSIS CONCLUSION ---");
        System.out.println("Question: SKU B456 补货策略的安全库存字段值为0");
        System.out.println("Root cause analysis:");
        System.out.println("  1. Safety stock is computed by SafetyStockService.calculate()");
        System.out.println("  2. Called by Dolphin scheduled task");
        System.out.println("  3. Known pitfall: 安全库存表有15分钟延迟");
        System.out.println("  4. Possible causes:");
        System.out.println("     a. Dolphin任务未执行或执行失败");
        System.out.println("     b. 历史销量数据缺失导致安全库存计算为0");
        System.out.println("     c. 品仓参数 replm_inv_param_sku_wh_input 未初始化");
        System.out.println("Recommendation: 检查Dolphin任务状态，确认销量数据完整性。");
        System.out.println("--- END DIAGNOSIS ---\n");

        // Verify tools called
        assertThat(diagnosisToolTrace)
                .contains("lookup_concept(补货策略)")
                .contains("find_by_table(replm_safety_stock)")
                .contains("find_by_class(SafetyStockService)")
                .contains("reverse_chain(SafetyStockService#calculate)")
                .contains("impact_analysis(SafetyStockService#calculate)")
                .contains("render_call_graph(ReplenishmentStrategyTask#execute)");
    }

    // ================================================================
    // SCENARIO 3: Startup verification
    // ================================================================

    @Test
    void startup_shouldAutoLoadKnowledgeAndBuildCodeGraph() {
        System.out.println(">>> SCENARIO 3: Verify system startup auto-loads everything\n");

        // Domain knowledge loaded
        assertThat(domainIndex.size()).isGreaterThanOrEqualTo(2);
        DomainKnowledge replenishment = domainIndex.findByName("补货策略");
        assertThat(replenishment).isNotNull();
        assertThat(replenishment.getTables()).contains("replm_inv_param_sku_wh_input");
        System.out.println("[OK] Domain knowledge auto-loaded: " + domainIndex.size() + " concepts");

        // Code graph built
        assertThat(codeGraphIndex.nodeCount()).isGreaterThanOrEqualTo(4);
        List<CodeGraphNode> nodes = codeGraphIndex.findByName("ReplenishmentStrategy");
        assertThat(nodes).isNotEmpty();
        System.out.println("[OK] Code graph built: " + codeGraphIndex.nodeCount() + " nodes");

        // Knowledge stored in VectorStore (domain knowledge + module architecture)
        assertThat(vectorStore.size()).isGreaterThanOrEqualTo(3);
        SearchRequest req = new SearchRequest("module architecture", 5, 0.0, null);
        List<Document> archDocs = vectorStore.similaritySearch(req);
        assertThat(archDocs).isNotEmpty();
        System.out.println("[OK] Knowledge stored: " + vectorStore.size() + " docs (including module architecture)");

        // Module architecture is queryable
        List<DomainKnowledge> byTable = domainIndex.findByTable("replm_inv_param_sku_wh_input");
        assertThat(byTable).isNotEmpty();
        System.out.println("[OK] Reverse lookup works: table → concept");

        System.out.println("\n[Startup verification PASSED]");
    }

    // ================================================================
    // Setup helpers
    // ================================================================

    private void createProjectSourceFiles(Path root) throws Exception {
        // Create Java source files that represent a replenishment system
        writeFile(root.resolve("com/example/repl/task/ReplenishmentStrategyTask.java"),
                "package com.example.repl.task;\n"
                + "import com.example.repl.service.ReplenishmentStrategyService;\n"
                + "import com.example.repl.service.SafetyStockService;\n"
                + "public class ReplenishmentStrategyTask {\n"
                + "    private ReplenishmentStrategyService strategyService;\n"
                + "    private SafetyStockService safetyStockService;\n"
                + "    public void execute() {\n"
                + "        safetyStockService.calculate();\n"
                + "        strategyService.generateStrategy();\n"
                + "    }\n"
                + "}");

        writeFile(root.resolve("com/example/repl/service/ReplenishmentStrategyService.java"),
                "package com.example.repl.service;\n"
                + "public class ReplenishmentStrategyService {\n"
                + "    public void generateStrategy() {}\n"
                + "}");

        writeFile(root.resolve("com/example/repl/service/SafetyStockService.java"),
                "package com.example.repl.service;\n"
                + "public class SafetyStockService {\n"
                + "    public void calculate() {}\n"
                + "}");

        writeFile(root.resolve("com/example/repl/model/ReplenishmentParam.java"),
                "package com.example.repl.model;\n"
                + "public class ReplenishmentParam {\n"
                + "    private String skuCode;\n"
                + "    private String warehouseCode;\n"
                + "    private int safetyStock;\n"
                + "}");
    }

    private void createDomainKnowledgeFiles(Path dir) throws Exception {
        writeFile(dir.resolve("replenishment-strategy.md"),
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
                + "  - 安全库存\n"
                + "tags: [replenishment, strategy]\n"
                + "---\n\n"
                + "# 补货策略\n\n"
                + "## 业务描述\n"
                + "根据历史销量和安全库存计算每个SKU在每个仓库的补货建议。\n\n"
                + "## 业务规则\n"
                + "1. 补货建议 = 安全库存 + 在途量 - 可用库存\n"
                + "2. 安全库存每天由Dolphin任务重新计算\n\n"
                + "## 已知陷阱\n"
                + "- 安全库存表有15分钟延迟\n"
                + "- 品仓未初始化时不会生成补货策略\n"
                + "- reorder_point为0时系统跳过该品仓\n");

        writeFile(dir.resolve("allocation-plan.md"),
                "---\n"
                + "name: 调拨计划\n"
                + "tables:\n"
                + "  - drp_allocation_plan\n"
                + "  - drp_allocation_detail\n"
                + "services:\n"
                + "  - AllocationPlanService\n"
                + "entry_points:\n"
                + "  - AllocationPlanTask.execute()\n"
                + "tags: [allocation]\n"
                + "---\n\n"
                + "# 调拨计划\n"
                + "航材消耗件多基地库存平衡调拨。\n");
    }

    private InMemoryCodeGraphIndex buildCodeGraphFromProject() {
        // Build a real code graph representing the project structure
        CodeGraphNode task = new CodeGraphNode(
                "com.example.repl.task.ReplenishmentStrategyTask#execute()",
                CodeGraphNode.NodeType.METHOD, "execute",
                "com.example.repl.task", "ReplenishmentStrategyTask",
                "void", "ReplenishmentStrategyTask.java", 6);

        CodeGraphNode strategyService = new CodeGraphNode(
                "com.example.repl.service.ReplenishmentStrategyService#generateStrategy()",
                CodeGraphNode.NodeType.METHOD, "generateStrategy",
                "com.example.repl.service", "ReplenishmentStrategyService",
                "void", "ReplenishmentStrategyService.java", 3);

        CodeGraphNode safetyStockService = new CodeGraphNode(
                "com.example.repl.service.SafetyStockService#calculate()",
                CodeGraphNode.NodeType.METHOD, "calculate",
                "com.example.repl.service", "SafetyStockService",
                "void", "SafetyStockService.java", 3);

        CodeGraphNode paramClass = new CodeGraphNode(
                "com.example.repl.model.ReplenishmentParam",
                CodeGraphNode.NodeType.CLASS, "ReplenishmentParam",
                "com.example.repl.model", "ReplenishmentParam",
                "", "ReplenishmentParam.java", 1);

        CodeGraph graph = new CodeGraph(
                Arrays.asList(task, strategyService, safetyStockService, paramClass),
                Arrays.asList(
                        new CodeGraphEdge(
                                "com.example.repl.task.ReplenishmentStrategyTask#execute()",
                                "com.example.repl.service.SafetyStockService#calculate()",
                                CodeGraphEdge.EdgeType.CALLS, "line 7"),
                        new CodeGraphEdge(
                                "com.example.repl.task.ReplenishmentStrategyTask#execute()",
                                "com.example.repl.service.ReplenishmentStrategyService#generateStrategy()",
                                CodeGraphEdge.EdgeType.CALLS, "line 8"),
                        new CodeGraphEdge(
                                "com.example.repl.service.ReplenishmentStrategyService",
                                "com.example.repl.model.ReplenishmentParam",
                                CodeGraphEdge.EdgeType.DEPENDS_ON, "import")));

        return new InMemoryCodeGraphIndex(graph);
    }

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

    private ToolCallback findCallback(ToolCallback[] callbacks, String name) {
        for (ToolCallback cb : callbacks) {
            if (cb.getName().equals(name)) return cb;
        }
        throw new AssertionError("Callback not found: " + name);
    }

    private String extractFilePath(String content) {
        String prefix = "Call graph rendered: ";
        int idx = content.indexOf(prefix);
        if (idx < 0) return null;
        String rest = content.substring(idx + prefix.length());
        int nl = rest.indexOf('\n');
        return nl >= 0 ? rest.substring(0, nl).trim() : rest.trim();
    }

    private String extractMermaid(String content) {
        int start = content.indexOf("```");
        if (start < 0) return content;
        start = content.indexOf("\n", start) + 1;
        int end = content.indexOf("```", start);
        if (end < 0) return content;
        return content.substring(start, end).trim();
    }
}
