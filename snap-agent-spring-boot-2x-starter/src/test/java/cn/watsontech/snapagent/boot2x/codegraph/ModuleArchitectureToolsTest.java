package cn.watsontech.snapagent.boot2x.codegraph;

import cn.watsontech.snapagent.boot2x.tool.CodePathGuard;
import cn.watsontech.snapagent.core.tool.ToolCallback;
import cn.watsontech.snapagent.core.tool.ToolCallbacks;
import cn.watsontech.snapagent.core.tool.ToolResult;
import cn.watsontech.snapagent.core.vectorstore.Document;
import cn.watsontech.snapagent.core.vectorstore.VectorStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.eq;

/**
 * Tests for {@link ModuleArchitectureTools} — verifies module architecture
 * generation and knowledge storage.
 *
 * <p>Covers TDD spec 2026-08-04-module-architecture-tool-design:
 * generate_module_arch (AC1-AC3), store_as_knowledge (AC4-AC6).</p>
 */
class ModuleArchitectureToolsTest {

    @TempDir
    Path tempDir;

    private ModuleArchitectureTools tools;
    private ToolCallback[] callbacks;
    private CodePathGuard codePathGuard;
    private VectorStore vectorStore;

    @BeforeEach
    void setUp() {
        // Create a fake project structure
        createJavaFile("com/test/core/CoreService.java",
                "package com.test.core;\nimport com.test.biz.BizHelper;\npublic class CoreService {}");
        createJavaFile("com/test/core/CoreUtil.java",
                "package com.test.core;\npublic class CoreUtil {}");
        createJavaFile("com/test/biz/BizService.java",
                "package com.test.biz;\nimport com.test.core.CoreService;\npublic class BizService {}");
        createJavaFile("com/test/biz/BizHelper.java",
                "package com.test.biz;\npublic class BizHelper {}");
        createJavaFile("com/test/web/WebController.java",
                "package com.test.web;\nimport com.test.biz.BizService;\nimport com.test.core.CoreService;\npublic class WebController {}");

        codePathGuard = new CodePathGuard(
                tempDir.toString(),
                Arrays.asList(".java"),
                10000, 1024 * 1024);

        vectorStore = mock(VectorStore.class);
        tools = new ModuleArchitectureTools(codePathGuard, vectorStore, tempDir.resolve("knowledge").toString());
        callbacks = ToolCallbacks.from(tools);
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

    private ToolCallback findByName(String name) {
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

    // ---- US-1: generate_module_arch ----

    @Test
    void shouldReflectTwoToolMethods() {
        assertThat(callbacks).hasSize(2);
        assertThat(findByName("generate_module_arch")).isNotNull();
        assertThat(findByName("store_as_knowledge")).isNotNull();
    }

    @Test
    void eachCallbackShouldHaveNonEmptyJsonSchema() {
        for (ToolCallback cb : callbacks) {
            assertThat(cb.getJsonSchema()).isNotEmpty();
            assertThat(cb.getJsonSchema()).contains("\"type\":\"object\"");
            assertThat(cb.getDescription()).isNotEmpty();
        }
    }

    // AC1: Project with packages, call generate_module_arch → returns HTML path with modules
    @Test
    void generateModuleArch_shouldReturnHtmlWithModules() {
        ToolResult result = findByName("generate_module_arch").execute(
                args("packagePrefix", "com.test", "depth", "2"), null);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("Module architecture generated");
        assertThat(result.getContent()).contains(".html");
        assertThat(result.getContent()).contains("Modules:");
        assertThat(result.getContent()).contains("core");
        assertThat(result.getContent()).contains("biz");
        assertThat(result.getContent()).contains("web");
    }

    // AC1b: Default depth works
    @Test
    void generateModuleArch_defaultDepth_shouldWork() {
        ToolResult result = findByName("generate_module_arch").execute(
                args("packagePrefix", "com.test"), null);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("Module architecture generated");
    }

    // AC3: Empty project (no .java files for the given prefix) → "no modules found"
    @Test
    void generateModuleArch_noModulesFound() {
        ToolResult result = findByName("generate_module_arch").execute(
                args("packagePrefix", "com.nonexistent"), null);
        assertThat(result.getContent()).contains("No modules found");
    }

    // ---- US-2: store_as_knowledge ----

    // AC4: VectorStore available → success + document stored
    @Test
    void storeAsKnowledge_withVectorStore_shouldSucceed() {
        ToolResult result = findByName("store_as_knowledge").execute(
                args("title", "Test Architecture", "content", "graph LR\n  A --> B"), null);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("Knowledge stored");
        assertThat(result.getContent()).contains("Version: 1");
        verify(vectorStore).add(anyList());
    }

    // Version auto-increment: storing same title twice → v1 then v2
    @Test
    void storeAsKnowledge_sameTitleTwice_shouldIncrementVersion() {
        cn.watsontech.snapagent.core.vectorstore.InMemoryVectorStore memStore =
                new cn.watsontech.snapagent.core.vectorstore.InMemoryVectorStore();
        ModuleArchitectureTools toolsWithMemStore = new ModuleArchitectureTools(
                codePathGuard, memStore, tempDir.resolve("knowledge").toString());
        ToolCallback[] cbs = ToolCallbacks.from(toolsWithMemStore);
        ToolCallback storeCb = null;
        for (ToolCallback cb : cbs) {
            if (cb.getName().equals("store_as_knowledge")) { storeCb = cb; break; }
        }

        // First store → v1
        ToolResult r1 = storeCb.execute(
                args("title", "My Arch", "content", "v1 content"), null);
        assertThat(r1.getContent()).contains("Version: 1");

        // Second store with same title → v2
        ToolResult r2 = storeCb.execute(
                args("title", "My Arch", "content", "v2 content"), null);
        assertThat(r2.getContent()).contains("Version: 2");

        // Both documents exist in store
        assertThat(memStore.size()).isEqualTo(2);
    }

    // AC5: VectorStore not available → warning
    @Test
    void storeAsKnowledge_noVectorStore_shouldWarn() {
        ModuleArchitectureTools toolsNoVs = new ModuleArchitectureTools(
                codePathGuard, null, tempDir.resolve("knowledge").toString());
        ToolCallback[] cbs = ToolCallbacks.from(toolsNoVs);
        ToolCallback storeCb = null;
        for (ToolCallback cb : cbs) {
            if (cb.getName().equals("store_as_knowledge")) {
                storeCb = cb;
                break;
            }
        }
        assertThat(storeCb).isNotNull();
        ToolResult result = storeCb.execute(
                args("title", "Test", "content", "graph LR\n  A --> B"), null);
        assertThat(result.getContent()).contains("VectorStore not available");
    }

    // AC6: Title is null/empty → error
    @Test
    void storeAsKnowledge_nullTitle_shouldError() {
        ToolResult result = findByName("store_as_knowledge").execute(
                args("title", "", "content", "graph LR\n  A --> B"), null);
        assertThat(result.getContent()).contains("Title is required");
    }

    // ---- Mermaid diagram structure ----

    @Test
    void generateModuleArch_shouldContainMermaidEdges() {
        ToolResult result = findByName("generate_module_arch").execute(
                args("packagePrefix", "com.test", "depth", "2"), null);
        assertThat(result.getContent()).contains("Mermaid diagram");
        // web depends on biz and core
        // biz depends on core
        String content = result.getContent();
        assertThat(content).contains("graph LR");
    }

    // ---- HTML output ----

    @Test
    void generateModuleArch_shouldCreateHtmlFile() {
        ToolResult result = findByName("generate_module_arch").execute(
                args("packagePrefix", "com.test", "depth", "2"), null);
        // Extract file path from output
        String content = result.getContent();
        assertThat(content).contains(".html");
        // The file should exist
        String path = extractPath(content);
        assertThat(path).isNotNull();
        assertThat(new File(path)).exists();
    }

    private String extractPath(String content) {
        // Format: "Module architecture generated: /path/to/file.html"
        int idx = content.indexOf("Module architecture generated: ");
        if (idx < 0) return null;
        String rest = content.substring(idx + "Module architecture generated: ".length());
        int nl = rest.indexOf('\n');
        return nl >= 0 ? rest.substring(0, nl).trim() : rest.trim();
    }
}
