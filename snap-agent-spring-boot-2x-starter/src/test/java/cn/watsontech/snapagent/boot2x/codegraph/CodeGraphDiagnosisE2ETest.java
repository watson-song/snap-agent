package cn.watsontech.snapagent.boot2x.codegraph;

import cn.watsontech.snapagent.boot2x.tool.CodePathGuard;
import cn.watsontech.snapagent.core.codegraph.CodeGraph;
import cn.watsontech.snapagent.core.codegraph.CodeGraphEdge;
import cn.watsontech.snapagent.core.codegraph.CodeGraphIndex;
import cn.watsontech.snapagent.core.codegraph.CodeGraphNode;
import cn.watsontech.snapagent.core.tool.ToolCallback;
import cn.watsontech.snapagent.core.tool.ToolCallbacks;
import cn.watsontech.snapagent.core.tool.ToolResult;
import cn.watsontech.snapagent.core.vectorstore.Document;
import cn.watsontech.snapagent.core.vectorstore.InMemoryVectorStore;
import cn.watsontech.snapagent.core.vectorstore.SearchRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
 * E2E test for the diagnosis workflow: module architecture → knowledge storage
 * → RAG retrieval → CodeGraph drill-down → call graph visualization.
 *
 * <p>Simulates the full pipeline:</p>
 * <ol>
 *   <li>Set up a fake project (replenishment system with packages)</li>
 *   <li>Generate module architecture diagram</li>
 *   <li>Store architecture as knowledge in VectorStore</li>
 *   <li>Query VectorStore (RAG) to retrieve module context</li>
 *   <li>Use CodeGraph tools to drill into method-level call chains</li>
 *   <li>Render call graph as interactive HTML</li>
 * </ol>
 *
 * <p>This test verifies that the architecture-diagram + codegraph + visualization
 * pipeline works end-to-end, without any LLM involvement.</p>
 */
class CodeGraphDiagnosisE2ETest {

    @TempDir
    Path tempDir;

    private CodePathGuard codePathGuard;
    private CodeGraphIndex codeGraphIndex;
    private CodeGraphTools codeGraphTools;
    private ModuleArchitectureTools moduleArchTools;
    private InMemoryVectorStore vectorStore;

    // Tool callbacks for direct invocation
    private ToolCallback[] codeGraphCallbacks;
    private ToolCallback[] moduleArchCallbacks;

    @BeforeEach
    void setUp() {
        // ---- Step 1: Create a fake replenishment project ----
        // Package structure:
        //   com.example.repl.task      → ReplenishmentPlanTask (entry point)
        //   com.example.repl.service   → AllocationPlanService, InventoryCheckService
        //   com.example.repl.repo      → AllocationPlanRepository
        //   com.example.repl.model     → AllocationPlan, InventoryRecord
        //   com.example.inventory.api  → InventoryApi (external dependency)

        createJavaFile("com/example/repl/task/ReplenishmentPlanTask.java",
                "package com.example.repl.task;\n"
                + "import com.example.repl.service.AllocationPlanService;\n"
                + "import com.example.repl.service.InventoryCheckService;\n"
                + "public class ReplenishmentPlanTask {\n"
                + "    private AllocationPlanService planService;\n"
                + "    private InventoryCheckService checkService;\n"
                + "    public void generate() {\n"
                + "        if (checkService.check()) {\n"
                + "            planService.createPlan();\n"
                + "        }\n"
                + "    }\n"
                + "}");

        createJavaFile("com/example/repl/service/AllocationPlanService.java",
                "package com.example.repl.service;\n"
                + "import com.example.repl.repo.AllocationPlanRepository;\n"
                + "import com.example.repl.model.AllocationPlan;\n"
                + "public class AllocationPlanService {\n"
                + "    private AllocationPlanRepository repo;\n"
                + "    public void createPlan() {\n"
                + "        AllocationPlan plan = new AllocationPlan();\n"
                + "        repo.save(plan);\n"
                + "    }\n"
                + "}");

        createJavaFile("com/example/repl/service/InventoryCheckService.java",
                "package com.example.repl.service;\n"
                + "import com.example.inventory.api.InventoryApi;\n"
                + "import com.example.repl.model.InventoryRecord;\n"
                + "public class InventoryCheckService {\n"
                + "    private InventoryApi inventoryApi;\n"
                + "    public boolean check() {\n"
                + "        InventoryRecord record = inventoryApi.query();\n"
                + "        return record != null;\n"
                + "    }\n"
                + "}");

        createJavaFile("com/example/repl/repo/AllocationPlanRepository.java",
                "package com.example.repl.repo;\n"
                + "import com.example.repl.model.AllocationPlan;\n"
                + "public class AllocationPlanRepository {\n"
                + "    public void save(AllocationPlan plan) {}\n"
                + "}");

        createJavaFile("com/example/repl/model/AllocationPlan.java",
                "package com.example.repl.model;\n"
                + "public class AllocationPlan {\n"
                + "    private String skuCode;\n"
                + "    private int quantity;\n"
                + "}");

        createJavaFile("com/example/repl/model/InventoryRecord.java",
                "package com.example.repl.model;\n"
                + "public class InventoryRecord {\n"
                + "    private String skuCode;\n"
                + "    private int stock;\n"
                + "}");

        createJavaFile("com/example/inventory/api/InventoryApi.java",
                "package com.example.inventory.api;\n"
                + "import com.example.repl.model.InventoryRecord;\n"
                + "public class InventoryApi {\n"
                + "    public InventoryRecord query() { return null; }\n"
                + "}");

        // ---- Step 2: Set up CodePathGuard and CodeGraphIndex ----
        codePathGuard = new CodePathGuard(
                tempDir.toString(),
                Arrays.asList(".java"),
                10000, 1024 * 1024);

        // Build a CodeGraph manually to simulate what SimpleCodeGraphBuilder would do
        codeGraphIndex = buildCodeGraph();

        // ---- Step 3: Set up tools ----
        vectorStore = new InMemoryVectorStore();
        String knowledgeDir = tempDir.resolve("knowledge").toString();
        String graphOutputDir = tempDir.resolve("graphs").toString();

        codeGraphTools = new CodeGraphTools(codeGraphIndex, 5, 3,
                new ChineseCodeGraphMessages(), graphOutputDir);
        moduleArchTools = new ModuleArchitectureTools(codePathGuard, vectorStore, knowledgeDir);

        codeGraphCallbacks = ToolCallbacks.from(codeGraphTools);
        moduleArchCallbacks = ToolCallbacks.from(moduleArchTools);
    }

    private void createJavaFile(String relativePath, String content) {
        try {
            File file = tempDir.resolve(relativePath).toFile();
            file.getParentFile().mkdirs();
            OutputStreamWriter writer = new OutputStreamWriter(
                    new FileOutputStream(file), StandardCharsets.UTF_8);
            try {
                writer.write(content);
            } finally {
                writer.close();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Build a CodeGraph that represents the replenishment system's call relationships.
     */
    private CodeGraphIndex buildCodeGraph() {
        // Nodes
        CodeGraphNode task = new CodeGraphNode(
                "com.example.repl.task.ReplenishmentPlanTask#generate()",
                CodeGraphNode.NodeType.METHOD, "generate",
                "com.example.repl.task", "ReplenishmentPlanTask",
                "void", "com/example/repl/task/ReplenishmentPlanTask.java", 7);

        CodeGraphNode planService = new CodeGraphNode(
                "com.example.repl.service.AllocationPlanService#createPlan()",
                CodeGraphNode.NodeType.METHOD, "createPlan",
                "com.example.repl.service", "AllocationPlanService",
                "void", "com/example/repl/service/AllocationPlanService.java", 5);

        CodeGraphNode checkService = new CodeGraphNode(
                "com.example.repl.service.InventoryCheckService#check()",
                CodeGraphNode.NodeType.METHOD, "check",
                "com.example.repl.service", "InventoryCheckService",
                "boolean", "com/example/repl/service/InventoryCheckService.java", 5);

        CodeGraphNode repo = new CodeGraphNode(
                "com.example.repl.repo.AllocationPlanRepository#save(AllocationPlan)",
                CodeGraphNode.NodeType.METHOD, "save",
                "com.example.repl.repo", "AllocationPlanRepository",
                "void", "com/example/repl/repo/AllocationPlanRepository.java", 3);

        CodeGraphNode inventoryApi = new CodeGraphNode(
                "com.example.inventory.api.InventoryApi#query()",
                CodeGraphNode.NodeType.METHOD, "query",
                "com.example.inventory.api", "InventoryApi",
                "InventoryRecord", "com/example/inventory/api/InventoryApi.java", 3);

        CodeGraphNode planClass = new CodeGraphNode(
                "com.example.repl.model.AllocationPlan",
                CodeGraphNode.NodeType.CLASS, "AllocationPlan",
                "com.example.repl.model", "AllocationPlan",
                "", "com/example/repl/model/AllocationPlan.java", 1);

        CodeGraphNode recordClass = new CodeGraphNode(
                "com.example.repl.model.InventoryRecord",
                CodeGraphNode.NodeType.CLASS, "InventoryRecord",
                "com.example.repl.model", "InventoryRecord",
                "", "com/example/repl/model/InventoryRecord.java", 1);

        // Edges
        CodeGraphEdge e1 = new CodeGraphEdge(
                "com.example.repl.task.ReplenishmentPlanTask#generate()",
                "com.example.repl.service.InventoryCheckService#check()",
                CodeGraphEdge.EdgeType.CALLS, "line 8");
        CodeGraphEdge e2 = new CodeGraphEdge(
                "com.example.repl.task.ReplenishmentPlanTask#generate()",
                "com.example.repl.service.AllocationPlanService#createPlan()",
                CodeGraphEdge.EdgeType.CALLS, "line 9");
        CodeGraphEdge e3 = new CodeGraphEdge(
                "com.example.repl.service.AllocationPlanService#createPlan()",
                "com.example.repl.repo.AllocationPlanRepository#save(AllocationPlan)",
                CodeGraphEdge.EdgeType.CALLS, "line 6");
        CodeGraphEdge e4 = new CodeGraphEdge(
                "com.example.repl.service.InventoryCheckService#check()",
                "com.example.inventory.api.InventoryApi#query()",
                CodeGraphEdge.EdgeType.CALLS, "line 6");
        CodeGraphEdge e5 = new CodeGraphEdge(
                "com.example.repl.service.AllocationPlanService",
                "com.example.repl.model.AllocationPlan",
                CodeGraphEdge.EdgeType.DEPENDS_ON, "import");
        CodeGraphEdge e6 = new CodeGraphEdge(
                "com.example.repl.service.InventoryCheckService",
                "com.example.repl.model.InventoryRecord",
                CodeGraphEdge.EdgeType.DEPENDS_ON, "import");
        CodeGraphEdge e7 = new CodeGraphEdge(
                "com.example.repl.service.InventoryCheckService",
                "com.example.inventory.api.InventoryApi",
                CodeGraphEdge.EdgeType.DEPENDS_ON, "import");

        CodeGraph graph = new CodeGraph(
                Arrays.asList(task, planService, checkService, repo, inventoryApi, planClass, recordClass),
                Arrays.asList(e1, e2, e3, e4, e5, e6, e7));

        return new InMemoryCodeGraphIndex(graph);
    }

    private ToolCallback findCallback(ToolCallback[] callbacks, String name) {
        for (ToolCallback cb : callbacks) {
            if (cb.getName().equals(name)) return cb;
        }
        throw new AssertionError("callback not found: " + name);
    }

    private Map<String, Object> args(String... kvPairs) {
        Map<String, Object> map = new HashMap<String, Object>();
        for (int i = 0; i < kvPairs.length; i += 2) {
            if (kvPairs[i + 1] != null) {
                map.put(kvPairs[i], kvPairs[i + 1]);
            }
        }
        return map;
    }

    // =========================================================================
    // E2E Test: Full Diagnosis Workflow
    // =========================================================================

    /**
     * Simulates the complete diagnosis flow:
     * "Why wasn't a replenishment plan generated for SKU A123?"
     *
     * <p>Steps:</p>
     * <ol>
     *   <li>Pre-generation: generate module architecture + store as knowledge</li>
     *   <li>Diagnosis: RAG retrieves module context</li>
     *   <li>Drill-down: CodeGraph traces method call chains</li>
     *   <li>Visualization: render call graph as HTML</li>
     * </ol>
     */
    @Test
    void fullDiagnosisWorkflow_moduleArchToCallGraphVisualization() {
        // ============================================================
        // PHASE 1: Pre-generation (done at startup / CI time)
        // ============================================================

        // Step 1a: Generate module architecture
        Map<String, Object> genArgs = args("packagePrefix", "com.example", "depth", "2");
        ToolResult archResult = findCallback(moduleArchCallbacks, "generate_module_arch")
                .execute(genArgs, null);
        assertThat(archResult.isSuccess()).isTrue();
        assertThat(archResult.getContent()).contains("Module architecture generated");
        assertThat(archResult.getContent()).contains("repl.task");
        assertThat(archResult.getContent()).contains("repl.service");
        assertThat(archResult.getContent()).contains("repl.repo");
        assertThat(archResult.getContent()).contains("repl.model");
        assertThat(archResult.getContent()).contains("inventory.api");
        System.out.println("[Phase 1a] Module architecture generated:\n" + archResult.getContent());

        // Extract the Mermaid diagram from the result
        String archContent = archResult.getContent();
        String mermaidContent = extractMermaid(archContent);
        assertThat(mermaidContent).isNotEmpty();
        assertThat(mermaidContent).contains("graph LR");

        // Step 1b: Store as knowledge
        Map<String, Object> storeArgs = args(
                "title", "Replenishment System - Module Architecture",
                "content", mermaidContent,
                "tags", "replenishment,allocation,module-architecture");
        ToolResult storeResult = findCallback(moduleArchCallbacks, "store_as_knowledge")
                .execute(storeArgs, null);
        assertThat(storeResult.isSuccess()).isTrue();
        assertThat(storeResult.getContent()).contains("Knowledge stored");
        System.out.println("[Phase 1b] Stored as knowledge: " + storeResult.getContent());

        // ============================================================
        // PHASE 2: Diagnosis time (user asks a question)
        // ============================================================

        // Step 2a: RAG retrieval — user asks "why no allocation plan for SKU A123?"
        SearchRequest ragRequest = new SearchRequest(
                "replenishment allocation plan module architecture", 5, 0.0, null);
        List<Document> ragResults = vectorStore.similaritySearch(ragRequest);
        assertThat(ragResults).isNotEmpty();
        Document retrievedDoc = ragResults.get(0);
        assertThat(retrievedDoc.getContent()).contains("graph LR");
        assertThat((String) retrievedDoc.getMetadata("source")).isEqualTo("module-architecture");
        System.out.println("[Phase 2a] RAG retrieved: " + retrievedDoc.getContent().substring(0, Math.min(80, retrievedDoc.getContent().length())) + "...");

        // Step 2b: Now Agent knows the module structure. Drill into method-level with CodeGraph.
        // Agent calls reverse_chain: "Who calls AllocationPlanService.createPlan?"
        Map<String, Object> reverseArgs = args("query", "AllocationPlanService#createPlan");
        ToolResult reverseResult = findCallback(codeGraphCallbacks, "reverse_chain")
                .execute(reverseArgs, null);
        assertThat(reverseResult.isSuccess()).isTrue();
        assertThat(reverseResult.getContent()).contains("反向调用链");
        assertThat(reverseResult.getContent()).contains("ReplenishmentPlanTask#generate");
        System.out.println("[Phase 2b] Reverse chain: " + reverseResult.getContent());

        // Step 2c: Agent calls call_chain: "What does ReplenishmentPlanTask.generate() call?"
        Map<String, Object> callArgs = args("query", "ReplenishmentPlanTask#generate");
        ToolResult callResult = findCallback(codeGraphCallbacks, "call_chain")
                .execute(callArgs, null);
        assertThat(callResult.isSuccess()).isTrue();
        assertThat(callResult.getContent()).contains("正向调用链");
        assertThat(callResult.getContent()).contains("InventoryCheckService#check");
        assertThat(callResult.getContent()).contains("AllocationPlanService#createPlan");
        System.out.println("[Phase 2c] Forward chain: " + callResult.getContent());

        // Step 2d: Agent calls impact_analysis: "If we modify InventoryCheckService, what's affected?"
        Map<String, Object> impactArgs = args("query", "InventoryCheckService#check");
        ToolResult impactResult = findCallback(codeGraphCallbacks, "impact_analysis")
                .execute(impactArgs, null);
        assertThat(impactResult.isSuccess()).isTrue();
        assertThat(impactResult.getContent()).contains("变更影响范围");
        assertThat(impactResult.getContent()).contains("ReplenishmentPlanTask#generate");
        System.out.println("[Phase 2d] Impact analysis: " + impactResult.getContent());

        // ============================================================
        // PHASE 3: Visualization
        // ============================================================

        // Step 3a: Render the call chain as HTML
        Map<String, Object> renderArgs = args(
                "query", "ReplenishmentPlanTask#generate",
                "graphType", "call_chain",
                "maxDepth", "3");
        ToolResult renderResult = findCallback(codeGraphCallbacks, "render_call_graph")
                .execute(renderArgs, null);
        assertThat(renderResult.isSuccess()).isTrue();
        assertThat(renderResult.getContent()).contains("Call graph rendered");
        assertThat(renderResult.getContent()).contains(".html");
        assertThat(renderResult.getContent()).contains("Nodes:");
        assertThat(renderResult.getContent()).contains("Edges:");
        // Verify the HTML file was actually created
        String htmlPath = extractFilePath(renderResult.getContent());
        assertThat(htmlPath).isNotNull();
        assertThat(new File(htmlPath)).exists();
        System.out.println("[Phase 3a] Call graph rendered: " + htmlPath);

        // Step 3b: Also render impact analysis
        Map<String, Object> impactRenderArgs = args(
                "query", "InventoryCheckService#check",
                "graphType", "impact",
                "maxDepth", "2");
        ToolResult impactRenderResult = findCallback(codeGraphCallbacks, "render_call_graph")
                .execute(impactRenderArgs, null);
        assertThat(impactRenderResult.isSuccess()).isTrue();
        assertThat(impactRenderResult.getContent()).contains("Call graph rendered");
        String impactHtmlPath = extractFilePath(impactRenderResult.getContent());
        assertThat(new File(impactHtmlPath)).exists();
        System.out.println("[Phase 3b] Impact graph rendered: " + impactHtmlPath);

        // ============================================================
        // PHASE 4: Verify the full pipeline produced useful artifacts
        // ============================================================

        // Verify VectorStore has the knowledge document
        assertThat(vectorStore.size()).isGreaterThanOrEqualTo(1);

        // Verify the diagnosis conclusion can be formed:
        // - RAG found the module architecture (module-level context)
        // - CodeGraph traced the call chain (code-level evidence)
        // - HTML visualization was generated (actionable output)
        System.out.println("\n[Summary]");
        System.out.println("  VectorStore docs: " + vectorStore.size());
        System.out.println("  CodeGraph nodes: " + codeGraphIndex.nodeCount());
        System.out.println("  Call chain HTML: " + htmlPath);
        System.out.println("  Impact graph HTML: " + impactHtmlPath);
        System.out.println("  Diagnosis conclusion:");
        System.out.println("    ReplenishmentPlanTask.generate()");
        System.out.println("      → InventoryCheckService.check()");
        System.out.println("        → InventoryApi.query()");
        System.out.println("      → AllocationPlanService.createPlan()");
        System.out.println("        → AllocationPlanRepository.save()");
    }

    /**
     * Test the scenario where RAG doesn't find anything (no pre-generated architecture).
     * Agent should still work with CodeGraph tools alone.
     */
    @Test
    void diagnosisWithoutPriorKnowledge_codeGraphStillWorks() {
        // No pre-generated architecture — VectorStore is empty
        assertThat(vectorStore.size()).isEqualTo(0);

        // Agent falls back to CodeGraph tools directly
        Map<String, Object> findArgs = args("query", "AllocationPlanService");
        ToolResult findResult = findCallback(codeGraphCallbacks, "find")
                .execute(findArgs, null);
        assertThat(findResult.isSuccess()).isTrue();
        assertThat(findResult.getContent()).contains("AllocationPlanService");

        // Agent can still trace call chains
        Map<String, Object> callArgs = args("query", "ReplenishmentPlanTask#generate");
        ToolResult callResult = findCallback(codeGraphCallbacks, "call_chain")
                .execute(callArgs, null);
        assertThat(callResult.isSuccess()).isTrue();
        assertThat(callResult.getContent()).contains("AllocationPlanService#createPlan");
        assertThat(callResult.getContent()).contains("InventoryCheckService#check");
    }

    /**
     * Test that the module architecture diagram correctly captures
     * cross-module dependencies (repl → inventory).
     */
    @Test
    void moduleArchitecture_capturesCrossModuleDependencies() {
        Map<String, Object> genArgs = args("packagePrefix", "com.example", "depth", "2");
        ToolResult result = findCallback(moduleArchCallbacks, "generate_module_arch")
                .execute(genArgs, null);
        assertThat(result.isSuccess()).isTrue();

        String content = result.getContent();
        // Should show repl modules depend on inventory module
        assertThat(content).contains("repl.service");
        assertThat(content).contains("inventory.api");
        assertThat(content).contains("Dependencies:");

        // Extract and verify Mermaid diagram has cross-module edge
        String mermaid = extractMermaid(content);
        assertThat(mermaid).contains("graph LR");
        // There should be an edge from repl service to inventory
        assertThat(mermaid).contains("-->");
    }

    /**
     * Test storing multiple module architectures for different subsystems
     * and retrieving them selectively via RAG.
     */
    @Test
    void multipleModuleArchitectures_selectiveRetrieval() {
        // Store architecture for replenishment module
        Map<String, Object> store1 = args(
                "title", "Replenishment Module Architecture",
                "content", "graph LR\n  repl_task --> repl_service\n  repl_service --> repl_repo",
                "tags", "replenishment");
        findCallback(moduleArchCallbacks, "store_as_knowledge").execute(store1, null);

        // Store architecture for inventory module
        Map<String, Object> store2 = args(
                "title", "Inventory Module Architecture",
                "content", "graph LR\n  inventory_api --> inventory_service\n  inventory_service --> inventory_repo",
                "tags", "inventory");
        findCallback(moduleArchCallbacks, "store_as_knowledge").execute(store2, null);

        // Both stored
        assertThat(vectorStore.size()).isEqualTo(2);

        // Query for replenishment — should find replenishment doc
        SearchRequest req1 = new SearchRequest("replenishment module", 1, 0.0, null);
        List<Document> results1 = vectorStore.similaritySearch(req1);
        assertThat(results1).isNotEmpty();
        assertThat(results1.get(0).getContent()).contains("repl_task");

        // Query for inventory — should find inventory doc
        SearchRequest req2 = new SearchRequest("inventory module", 1, 0.0, null);
        List<Document> results2 = vectorStore.similaritySearch(req2);
        assertThat(results2).isNotEmpty();
        assertThat(results2.get(0).getContent()).contains("inventory_api");
    }

    // ---- Helper methods ----

    private String extractMermaid(String content) {
        int start = content.indexOf("```");
        if (start < 0) return "";
        start = content.indexOf("\n", start) + 1;
        int end = content.indexOf("```", start);
        if (end < 0) return "";
        return content.substring(start, end).trim();
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
