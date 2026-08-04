package cn.watsontech.snapagent.boot2x.codegraph;

import cn.watsontech.snapagent.boot2x.tool.CodePathGuard;
import cn.watsontech.snapagent.core.tool.Tool;
import cn.watsontech.snapagent.core.tool.ToolParam;
import cn.watsontech.snapagent.core.vectorstore.Document;
import cn.watsontech.snapagent.core.vectorstore.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Module architecture tools for generating and storing module dependency diagrams.
 *
 * <p>Provides two tools:</p>
 * <ul>
 *   <li>{@code generate_module_arch} — scans project source tree, generates module-level
 *       dependency diagrams (Mermaid + interactive HTML)</li>
 *   <li>{@code store_as_knowledge} — stores architecture diagrams in VectorStore as
 *       knowledge documents for RAG retrieval during diagnosis</li>
 * </ul>
 *
 * <p>These tools bridge the gap between module-level architecture understanding
 * (via RAG) and method-level code navigation (via CodeGraph tools).</p>
 */
public class ModuleArchitectureTools {

    private static final Logger log = LoggerFactory.getLogger(ModuleArchitectureTools.class);

    private static final Pattern PACKAGE_PATTERN =
            Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern IMPORT_PATTERN =
            Pattern.compile("^\\s*import\\s+(?:static\\s+)?([\\w.]+)\\s*;", Pattern.MULTILINE);

    private final CodePathGuard codePathGuard;
    private final VectorStore vectorStore;
    private final String knowledgeDir;

    public ModuleArchitectureTools(CodePathGuard codePathGuard, VectorStore vectorStore,
                                   String knowledgeDir) {
        this.codePathGuard = codePathGuard;
        this.vectorStore = vectorStore;
        this.knowledgeDir = knowledgeDir;
    }

    @Tool(name = "generate_module_arch",
          description = "扫描项目源码包结构，生成模块级依赖关系图。输出 Mermaid 图 + 可交互 HTML 文件。用于理解项目模块划分和依赖方向。")
    public String generateModuleArch(
            @ToolParam(description = "根包前缀（可选，默认扫描所有包），如 'com.test'") String packagePrefix,
            @ToolParam(description = "包深度（可选，默认2），如 depth=2 表示按 com.test.xx 分组", required = false) Integer depth) {
        int d = depth != null ? depth : 2;
        String prefix = packagePrefix != null ? packagePrefix : "";

        try {
            Path root = codePathGuard.getProjectRoot();
            if (root == null) {
                return "Error: Project root not configured or not accessible.";
            }

            // Scan all .java files
            Map<String, Set<String>> moduleImports = scanModules(root, prefix, d);
            if (moduleImports.isEmpty()) {
                return "No modules found for prefix '" + prefix + "' (depth=" + d + "). "
                        + "Check that packagePrefix matches your project packages.";
            }

            // Count classes per module
            Map<String, Integer> moduleClassCount = countClassesPerModule(root, prefix, d);

            // Build Mermaid diagram
            String mermaid = buildModuleDiagram(moduleImports, moduleClassCount);

            // Write HTML
            String htmlPath = writeHtml(mermaid, prefix);

            StringBuilder sb = new StringBuilder();
            sb.append("Module architecture generated: ").append(htmlPath).append("\n");
            sb.append("  Modules: ").append(moduleImports.size()).append("\n");
            int edgeCount = 0;
            for (Set<String> deps : moduleImports.values()) {
                edgeCount += deps.size();
            }
            sb.append("  Dependencies: ").append(edgeCount).append("\n\n");
            sb.append("Mermaid diagram:\n```\n").append(mermaid).append("\n```\n");
            return sb.toString();
        } catch (Exception e) {
            log.warn("Failed to generate module architecture: {}", e.getMessage());
            return "Error generating module architecture: " + e.getMessage();
        }
    }

    @Tool(name = "store_as_knowledge",
          description = "将架构图或模块分析结果存入知识库（VectorStore），诊断时可通过 RAG 检索。用于预生成模块架构图供后续诊断使用。")
    public String storeAsKnowledge(
            @ToolParam(description = "文档标题，如 'Module Architecture: cn.watsontech.snapagent'") String title,
            @ToolParam(description = "文档内容（Mermaid 图或文字描述）") String content,
            @ToolParam(description = "标签（可选，逗号分隔），如 'replenishment,allocation'", required = false) String tags) {
        if (title == null || title.trim().isEmpty()) {
            return "Error: Title is required for knowledge storage.";
        }
        if (content == null || content.trim().isEmpty()) {
            return "Error: Content is required for knowledge storage.";
        }

        if (vectorStore == null) {
            return "Warning: VectorStore not available. Knowledge cannot be stored. "
                    + "Enable a VectorStore implementation to use this feature.";
        }

        // Build document with metadata
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("source", "module-architecture");
        metadata.put("title", title);
        metadata.put("generatedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        if (tags != null && !tags.trim().isEmpty()) {
            metadata.put("tags", tags);
        }

        // Version tracking: find existing docs with same title, auto-increment version
        int version = 1;
        if (vectorStore instanceof cn.watsontech.snapagent.core.vectorstore.InMemoryVectorStore) {
            cn.watsontech.snapagent.core.vectorstore.InMemoryVectorStore memStore =
                    (cn.watsontech.snapagent.core.vectorstore.InMemoryVectorStore) vectorStore;
            for (Document existing : memStore.listAll()) {
                Object existTitle = existing.getMetadata("title");
                if (title.equals(existTitle)) {
                    Object existVersion = existing.getMetadata("version");
                    if (existVersion instanceof Number) {
                        int ev = ((Number) existVersion).intValue();
                        if (ev >= version) version = ev + 1;
                    }
                }
            }
        }
        metadata.put("version", version);

        Document doc = new Document(content, metadata);
        List<Document> docs = new ArrayList<Document>();
        docs.add(doc);
        vectorStore.add(docs);

        // Also write to knowledge directory for file-based ingestion
        String filePath = writeKnowledgeFile(title, content);

        StringBuilder sb = new StringBuilder();
        sb.append("Knowledge stored successfully.\n");
        sb.append("  Document ID: ").append(doc.getId()).append("\n");
        sb.append("  Title: ").append(title).append("\n");
        sb.append("  Version: ").append(version).append("\n");
        if (filePath != null) {
            sb.append("  File: ").append(filePath).append("\n");
        }
        return sb.toString();
    }

    // ---- Internal implementation ----

    private Map<String, Set<String>> scanModules(Path root, String prefix, int depth) throws IOException {
        // module name → set of dependent module names
        Map<String, Set<String>> moduleDeps = new TreeMap<String, Set<String>>();

        List<Path> javaFiles = new ArrayList<Path>();
        collectJavaFiles(root, javaFiles);

        for (Path file : javaFiles) {
            try {
                String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                Matcher pkgMatcher = PACKAGE_PATTERN.matcher(content);
                if (!pkgMatcher.find()) continue;

                String pkg = pkgMatcher.group(1);
                if (!prefix.isEmpty() && !pkg.startsWith(prefix)) continue;

                String moduleName = extractModuleName(pkg, prefix, depth);
                if (!moduleDeps.containsKey(moduleName)) {
                    moduleDeps.put(moduleName, new LinkedHashSet<String>());
                }

                // Find imports from other modules
                Matcher importMatcher = IMPORT_PATTERN.matcher(content);
                while (importMatcher.find()) {
                    String importPkg = importMatcher.group(1);
                    // Get package part of import (remove class name)
                    int lastDot = importPkg.lastIndexOf('.');
                    if (lastDot <= 0) continue;
                    String importPkgOnly = importPkg.substring(0, lastDot);

                    if (!prefix.isEmpty() && !importPkgOnly.startsWith(prefix)) continue;

                    String importedModule = extractModuleName(importPkgOnly, prefix, depth);
                    if (!importedModule.equals(moduleName)) {
                        moduleDeps.get(moduleName).add(importedModule);
                    }
                }
            } catch (Exception e) {
                // Skip unreadable files
            }
        }

        return moduleDeps;
    }

    private void collectJavaFiles(Path dir, List<Path> result) {
        try {
            if (!Files.isDirectory(dir)) return;
            // Use list to avoid deep recursion on large projects
            Stream<Path> stream = Files.list(dir);
            try {
                List<Path> entries = new ArrayList<Path>();
                stream.forEach(new java.util.function.Consumer<Path>() {
                    public void accept(Path p) { entries.add(p); }
                });
                for (Path entry : entries) {
                    if (Files.isDirectory(entry)) {
                        // Skip hidden directories and target/build
                        String name = entry.getFileName().toString();
                        if (name.startsWith(".") || "target".equals(name) || "build".equals(name)
                                || "node_modules".equals(name)) {
                            continue;
                        }
                        collectJavaFiles(entry, result);
                    } else if (entry.toString().endsWith(".java")) {
                        result.add(entry);
                    }
                }
            } finally {
                stream.close();
            }
        } catch (IOException e) {
            // Skip inaccessible directories
        }
    }

    private Map<String, Integer> countClassesPerModule(Path root, String prefix, int depth) {
        Map<String, Integer> counts = new TreeMap<String, Integer>();
        List<Path> javaFiles = new ArrayList<Path>();
        collectJavaFiles(root, javaFiles);

        for (Path file : javaFiles) {
            try {
                String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                Matcher pkgMatcher = PACKAGE_PATTERN.matcher(content);
                if (!pkgMatcher.find()) continue;
                String pkg = pkgMatcher.group(1);
                if (!prefix.isEmpty() && !pkg.startsWith(prefix)) continue;
                String moduleName = extractModuleName(pkg, prefix, depth);
                Integer count = counts.get(moduleName);
                counts.put(moduleName, count == null ? 1 : count + 1);
            } catch (Exception e) {
                // skip
            }
        }
        return counts;
    }

    private String extractModuleName(String pkg, String prefix, int depth) {
        String remaining = prefix.isEmpty() ? pkg : pkg.substring(prefix.length());
        if (remaining.startsWith(".")) remaining = remaining.substring(1);
        if (remaining.isEmpty()) {
            // Package is exactly the prefix
            int lastDot = prefix.lastIndexOf('.');
            return lastDot >= 0 ? prefix.substring(lastDot + 1) : prefix;
        }

        String[] parts = remaining.split("\\.");
        StringBuilder name = new StringBuilder();
        int limit = Math.min(parts.length, depth);
        for (int i = 0; i < limit; i++) {
            if (i > 0) name.append(".");
            name.append(parts[i]);
        }
        return name.toString();
    }

    private String buildModuleDiagram(Map<String, Set<String>> moduleDeps,
                                       Map<String, Integer> classCounts) {
        StringBuilder sb = new StringBuilder();
        sb.append("graph LR\n");

        // Style classes
        sb.append("    classDef core fill:#4dabf7,stroke:#1971c2,color:#fff\n");
        sb.append("    classDef biz fill:#69db7c,stroke:#2b8a3e,color:#fff\n");
        sb.append("    classDef infra fill:#ffd43b,stroke:#e67700,color:#000\n");

        // Declare nodes
        int depCount = 0; // modules that are depended upon
        Set<String> dependedUpon = new LinkedHashSet<String>();
        for (Set<String> deps : moduleDeps.values()) {
            dependedUpon.addAll(deps);
        }

        for (String module : moduleDeps.keySet()) {
            String safeId = module.replaceAll("[^a-zA-Z0-9]", "_");
            int count = classCounts.containsKey(module) ? classCounts.get(module) : 0;
            sb.append("    ").append(safeId)
              .append("[\"").append(module).append("<br/>").append(count).append(" classes\"]\n");
        }

        // Declare edges
        for (Map.Entry<String, Set<String>> entry : moduleDeps.entrySet()) {
            String from = entry.getKey().replaceAll("[^a-zA-Z0-9]", "_");
            for (String to : entry.getValue()) {
                String safeTo = to.replaceAll("[^a-zA-Z0-9]", "_");
                sb.append("    ").append(from).append(" --> ").append(safeTo).append("\n");
            }
        }

        // Apply styles: modules with no dependencies = core, modules with deps = biz
        for (String module : moduleDeps.keySet()) {
            String safeId = module.replaceAll("[^a-zA-Z0-9]", "_");
            if (moduleDeps.get(module).isEmpty() && dependedUpon.contains(module)) {
                sb.append("    class ").append(safeId).append(" core\n");
            } else if (!moduleDeps.get(module).isEmpty()) {
                sb.append("    class ").append(safeId).append(" biz\n");
            }
        }

        return sb.toString();
    }

    private String writeHtml(String mermaid, String prefix) throws IOException {
        String dir = knowledgeDir != null ? knowledgeDir + "/module-arch"
                : System.getProperty("java.io.tmpdir") + "/snap-agent/module-arch";
        File dirFile = new File(dir);
        dirFile.mkdirs();

        String safeName = "module-arch-" + prefix.replaceAll("[^a-zA-Z0-9]", "_")
                + "-" + System.currentTimeMillis() + ".html";
        File file = new File(dir, safeName);

        String title = "Module Architecture" + (prefix.isEmpty() ? "" : ": " + prefix);
        String html = buildHtml(title, mermaid);

        OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(file), StandardCharsets.UTF_8);
        try {
            writer.write(html);
        } finally {
            writer.close();
        }

        return file.getAbsolutePath();
    }

    private String writeKnowledgeFile(String title, String content) {
        if (knowledgeDir == null) return null;
        try {
            File dir = new File(knowledgeDir + "/module-arch");
            dir.mkdirs();
            String safeName = title.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fff]", "_")
                    + ".md";
            File file = new File(dir, safeName);

            StringBuilder sb = new StringBuilder();
            sb.append("---\n");
            sb.append("title: ").append(title).append("\n");
            sb.append("source: module-architecture\n");
            sb.append("generatedAt: ").append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("\n");
            sb.append("---\n\n");
            sb.append("# ").append(title).append("\n\n");
            sb.append(content).append("\n");

            OutputStreamWriter writer = new OutputStreamWriter(
                    new FileOutputStream(file), StandardCharsets.UTF_8);
            try {
                writer.write(sb.toString());
            } finally {
                writer.close();
            }
            return file.getAbsolutePath();
        } catch (IOException e) {
            log.warn("Failed to write knowledge file: {}", e.getMessage());
            return null;
        }
    }

    private String buildHtml(String title, String mermaid) {
        return "<!DOCTYPE html>\n"
                + "<html lang=\"en\">\n"
                + "<head>\n"
                + "  <meta charset=\"UTF-8\">\n"
                + "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n"
                + "  <title>" + esc(title) + "</title>\n"
                + "  <script src=\"https://cdn.jsdelivr.net/npm/mermaid@10/dist/mermaid.min.js\"></script>\n"
                + "  <style>\n"
                + "    * { margin: 0; padding: 0; box-sizing: border-box; }\n"
                + "    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;\n"
                + "           background: #1a1a2e; color: #eee; min-height: 100vh; }\n"
                + "    .header { background: #16213e; padding: 16px 24px; border-bottom: 1px solid #0f3460;\n"
                + "              display: flex; justify-content: space-between; align-items: center; }\n"
                + "    .header h1 { font-size: 18px; font-weight: 600; color: #4dabf7; }\n"
                + "    .legend { display: flex; gap: 16px; font-size: 13px; }\n"
                + "    .legend-item { display: flex; align-items: center; gap: 6px; }\n"
                + "    .legend-dot { width: 12px; height: 12px; border-radius: 3px; }\n"
                + "    .dot-core { background: #4dabf7; }\n"
                + "    .dot-biz { background: #69db7c; }\n"
                + "    .dot-infra { background: #ffd43b; }\n"
                + "    .controls { padding: 12px 24px; background: #16213e; border-bottom: 1px solid #0f3460;\n"
                + "                display: flex; gap: 8px; align-items: center; }\n"
                + "    .controls button { background: #0f3460; color: #eee; border: 1px solid #1a4080;\n"
                + "                       padding: 6px 14px; border-radius: 4px; cursor: pointer; font-size: 13px; }\n"
                + "    .controls button:hover { background: #1a4080; }\n"
                + "    .graph-container { padding: 24px; display: flex; justify-content: center;\n"
                + "                       overflow: auto; }\n"
                + "    .mermaid { max-width: 100%; }\n"
                + "    .footer { padding: 12px 24px; background: #16213e; border-top: 1px solid #0f3460;\n"
                + "              text-align: center; font-size: 12px; color: #666; }\n"
                + "  </style>\n"
                + "</head>\n"
                + "<body>\n"
                + "  <div class=\"header\">\n"
                + "    <h1>" + esc(title) + "</h1>\n"
                + "    <div class=\"legend\">\n"
                + "      <div class=\"legend-item\"><div class=\"legend-dot dot-core\"></div>Core (no deps)</div>\n"
                + "      <div class=\"legend-item\"><div class=\"legend-dot dot-biz\"></div>Business (has deps)</div>\n"
                + "      <div class=\"legend-item\"><div class=\"legend-dot dot-infra\"></div>Infrastructure</div>\n"
                + "    </div>\n"
                + "  </div>\n"
                + "  <div class=\"controls\">\n"
                + "    <button onclick=\"zoomIn()\">Zoom +</button>\n"
                + "    <button onclick=\"zoomOut()\">Zoom -</button>\n"
                + "    <button onclick=\"resetZoom()\">Reset</button>\n"
                + "    <button onclick=\"exportSVG()\">Export SVG</button>\n"
                + "  </div>\n"
                + "  <div class=\"graph-container\">\n"
                + "    <div class=\"mermaid\">\n"
                + mermaid
                + "    </div>\n"
                + "  </div>\n"
                + "  <div class=\"footer\">Generated by SnapAgent ModuleArchitectureTools | Mermaid.js</div>\n"
                + "  <script>\n"
                + "    mermaid.initialize({ startOnLoad: true, theme: 'dark',\n"
                + "      flowchart: { curve: 'basis', padding: 20 } });\n"
                + "    var scale = 1;\n"
                + "    function zoomIn() { scale = Math.min(scale + 0.2, 3); applyZoom(); }\n"
                + "    function zoomOut() { scale = Math.max(scale - 0.2, 0.3); applyZoom(); }\n"
                + "    function resetZoom() { scale = 1; applyZoom(); }\n"
                + "    function applyZoom() {\n"
                + "      var svg = document.querySelector('.mermaid svg');\n"
                + "      if (svg) svg.style.transform = 'scale(' + scale + ')';\n"
                + "    }\n"
                + "    function exportSVG() {\n"
                + "      var svg = document.querySelector('.mermaid svg');\n"
                + "      if (!svg) return;\n"
                + "      var data = new XMLSerializer().serializeToString(svg);\n"
                + "      var blob = new Blob([data], {type: 'image/svg+xml'});\n"
                + "      var url = URL.createObjectURL(blob);\n"
                + "      var a = document.createElement('a');\n"
                + "      a.href = url; a.download = 'module-architecture.svg'; a.click();\n"
                + "      URL.revokeObjectURL(url);\n"
                + "    }\n"
                + "  </script>\n"
                + "</body>\n"
                + "</html>";
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
