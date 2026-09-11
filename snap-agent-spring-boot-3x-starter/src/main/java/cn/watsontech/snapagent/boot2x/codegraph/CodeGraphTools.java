package cn.watsontech.snapagent.boot2x.codegraph;

import cn.watsontech.snapagent.core.codegraph.CodeGraphEdge;
import cn.watsontech.snapagent.core.codegraph.CodeGraphIndex;
import cn.watsontech.snapagent.core.codegraph.CodeGraphNode;
import cn.watsontech.snapagent.core.tool.Tool;
import cn.watsontech.snapagent.core.tool.ToolParam;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 2.x code graph tools exposed via {@code @Tool} annotation methods.
 *
 * <p>Refactored from the 1.x {@code CodeGraphToolProvider} (which implemented the
 * now-removed {@code ToolProvider} SPI) to individual {@code @Tool} methods
 * discovered by {@link cn.watsontech.snapagent.core.tool.ToolCallbacks#from(Object)}.
 * Each method becomes a separate {@link cn.watsontech.snapagent.core.tool.ToolCallback}
 * with auto-generated JSON Schema, registered to
 * {@link cn.watsontech.snapagent.core.tool.ToolCallbackRegistry} alongside
 * built-in tools.</p>
 *
 * <p>Provides five tools for navigating code relationships:</p>
 * <ul>
 *   <li>{@code call_chain} — forward call chain (A→B→C)</li>
 *   <li>{@code reverse_chain} — reverse call chain (who calls X)</li>
 *   <li>{@code impact_analysis} — change impact scope</li>
 *   <li>{@code find} — name-based node lookup</li>
 *   <li>{@code code_view} — key source excerpt lookup (offline diagnosis)</li>
 * </ul>
 */
public class CodeGraphTools {

    private final CodeGraphIndex index;
    private final int defaultMaxDepth;
    private final int defaultMaxImpactDepth;
    private final CodeGraphMessages messages;
    private final String outputDir;

    public CodeGraphTools(CodeGraphIndex index, int defaultMaxDepth, int defaultMaxImpactDepth) {
        this(index, defaultMaxDepth, defaultMaxImpactDepth, new ChineseCodeGraphMessages(), System.getProperty("java.io.tmpdir") + "/snap-agent/graphs");
    }

    public CodeGraphTools(CodeGraphIndex index, int defaultMaxDepth, int defaultMaxImpactDepth,
                         CodeGraphMessages messages) {
        this(index, defaultMaxDepth, defaultMaxImpactDepth, messages, System.getProperty("java.io.tmpdir") + "/snap-agent/graphs");
    }

    public CodeGraphTools(CodeGraphIndex index, int defaultMaxDepth, int defaultMaxImpactDepth,
                         CodeGraphMessages messages, String outputDir) {
        this.index = index;
        this.defaultMaxDepth = defaultMaxDepth;
        this.defaultMaxImpactDepth = defaultMaxImpactDepth;
        this.messages = messages != null ? messages : new ChineseCodeGraphMessages();
        this.outputDir = outputDir;
    }

    public CodeGraphTools(CodeGraphIndex index) {
        this(index, 5, 3);
    }

    @Tool(name = "call_chain", description = "查找方法的正向调用链（此方法调用了哪些方法，逐层展开）。输入方法签名或方法名，返回调用层级树。")
    public String callChain(
            @ToolParam(description = "方法签名，如 'com.example.Foo#bar(String)' 或方法名 'bar'") String query,
            @ToolParam(description = "最大调用深度（可选，默认5）", required = false) Integer maxDepth) {
        int depth = maxDepth != null ? maxDepth : defaultMaxDepth;
        return handleCallChain(query, depth);
    }

    @Tool(name = "reverse_chain", description = "查找反向调用链（谁调用了此方法）。输入方法签名或方法名，返回所有调用方。")
    public String reverseChain(
            @ToolParam(description = "方法签名或方法名") String query,
            @ToolParam(description = "最大反向深度（可选，默认5）", required = false) Integer maxDepth) {
        int depth = maxDepth != null ? maxDepth : defaultMaxDepth;
        return handleReverseChain(query, depth);
    }

    @Tool(name = "impact_analysis", description = "变更影响分析：如果修改指定类或方法，会影响哪些下游代码。返回受影响的方法/类列表。")
    public String impactAnalysis(
            @ToolParam(description = "类名或方法签名，如 'InventoryService' 或 'com.example.Foo#check()'") String query,
            @ToolParam(description = "最大影响深度（可选，默认3）", required = false) Integer maxDepth) {
        int depth = maxDepth != null ? maxDepth : defaultMaxImpactDepth;
        return handleImpactAnalysis(query, depth);
    }

    @Tool(name = "find", description = "按名称模糊查找代码节点（类/方法/字段）。返回匹配的节点列表，包含文件位置。")
    public String find(
            @ToolParam(description = "名称模式（模糊匹配，不区分大小写）") String query) {
        return handleFind(query);
    }

    @Tool(name = "code_view", description = "查看关键业务代码片段。输入类名或方法签名，返回该节点在集成阶段抽取并随图谱持久化的代码片段（类声明+字段+方法签名，不含方法体），用于离线诊断代码问题。")
    public String codeView(
            @ToolParam(description = "类名或方法签名，如 'OrderService' 或 'com.example.Foo#check()'") String query) {
        return handleCodeView(query);
    }

    @Tool(name = "render_call_graph", description = "将调用链/影响范围渲染为可交互的 HTML 图形。先执行查询，再生成带 Mermaid.js 的 HTML 文件，返回文件路径。支持三种模式：call_chain（正向调用链）、reverse_chain（反向调用链）、impact（影响范围）。")
    public String renderCallGraph(
            @ToolParam(description = "类名或方法签名，如 'InventoryService' 或 'com.example.Foo#check()'") String query,
            @ToolParam(description = "图形类型：call_chain（正向调用链）、reverse_chain（反向调用链）、impact（影响范围）") String graphType,
            @ToolParam(description = "最大深度（可选，默认3）", required = false) Integer maxDepth) {
        int depth = maxDepth != null ? maxDepth : 3;
        return handleRenderCallGraph(query, graphType, depth);
    }

    // ---- Internal handlers (reused from 1.x CodeGraphToolProvider) ----

    private String handleRenderCallGraph(String query, String graphType, int maxDepth) {
        if (query == null || query.isEmpty()) {
            return messages.notFound();
        }
        List<CodeGraphNode> targets = resolveNodes(query);
        if (targets.isEmpty()) {
            return messages.notFoundQuery(query);
        }

        CodeGraphNode root = targets.get(0);
        String type = graphType != null ? graphType.toLowerCase() : "call_chain";

        // Collect nodes and edges based on graph type
        Set<String> nodeIds = new LinkedHashSet<String>();
        List<String[]> edges = new ArrayList<String[]>(); // [fromId, toId, label]
        nodeIds.add(root.getId());

        if ("call_chain".equals(type)) {
            collectForwardEdges(root.getId(), maxDepth, nodeIds, edges, new HashSet<String>());
        } else if ("reverse_chain".equals(type)) {
            collectReverseEdges(root.getId(), maxDepth, nodeIds, edges, new HashSet<String>());
        } else if ("impact".equals(type)) {
            collectImpactEdges(root.getId(), maxDepth, nodeIds, edges, new HashSet<String>());
        } else {
            return "Unsupported graph type: " + graphType + ". Use: call_chain, reverse_chain, impact";
        }

        if (nodeIds.size() <= 1) {
            return "No relationships found for '" + query + "' (type=" + type + ")";
        }

        // Build Mermaid diagram
        String mermaid = buildMermaidDiagram(root, type, nodeIds, edges);

        // Generate HTML file
        String filePath = writeHtmlFile(root.getId(), type, mermaid);
        if (filePath == null) {
            return "Failed to write HTML file for graph";
        }

        return "Call graph rendered: " + filePath
                + "\n  Nodes: " + nodeIds.size()
                + "\n  Edges: " + edges.size()
                + "\n  Type: " + type
                + "\n  Root: " + root.getId()
                + "\nOpen the HTML file in a browser to view the interactive graph.";
    }

    private void collectForwardEdges(String nodeId, int depth, Set<String> nodeIds,
                                     List<String[]> edges, Set<String> visited) {
        if (depth <= 0 || visited.contains(nodeId)) return;
        visited.add(nodeId);
        List<CodeGraphEdge> outEdges = index.getOutgoingEdges(nodeId);
        for (CodeGraphEdge edge : outEdges) {
            String toId = edge.getToId();
            nodeIds.add(toId);
            edges.add(new String[]{nodeId, toId, edge.getType().name()});
            collectForwardEdges(toId, depth - 1, nodeIds, edges, visited);
        }
    }

    private void collectReverseEdges(String nodeId, int depth, Set<String> nodeIds,
                                      List<String[]> edges, Set<String> visited) {
        if (depth <= 0 || visited.contains(nodeId)) return;
        visited.add(nodeId);
        List<CodeGraphEdge> inEdges = index.getIncomingEdges(nodeId);
        for (CodeGraphEdge edge : inEdges) {
            String fromId = edge.getFromId();
            nodeIds.add(fromId);
            edges.add(new String[]{fromId, nodeId, edge.getType().name()});
            collectReverseEdges(fromId, depth - 1, nodeIds, edges, visited);
        }
    }

    private void collectImpactEdges(String nodeId, int depth, Set<String> nodeIds,
                                     List<String[]> edges, Set<String> visited) {
        if (depth <= 0 || visited.contains(nodeId)) return;
        visited.add(nodeId);
        // Impact: all incoming edges (callers, dependents, etc.)
        List<CodeGraphEdge> inEdges = index.getIncomingEdges(nodeId);
        for (CodeGraphEdge edge : inEdges) {
            String fromId = edge.getFromId();
            nodeIds.add(fromId);
            edges.add(new String[]{fromId, nodeId, edge.getType().name()});
            collectImpactEdges(fromId, depth - 1, nodeIds, edges, visited);
        }
    }

    private String buildMermaidDiagram(CodeGraphNode root, String type,
                                        Set<String> nodeIds, List<String[]> edges) {
        StringBuilder sb = new StringBuilder();
        sb.append("graph TD\n");

        // Style root node
        String rootSafeId = sanitizeId(root.getId());
        sb.append("    classDef root fill:#ff6b6b,stroke:#c92a2a,color:#fff,font-weight:bold\n");
        sb.append("    classDef method fill:#4dabf7,stroke:#1971c2,color:#fff\n");
        sb.append("    classDef classNode fill:#69db7c,stroke:#2b8a3e,color:#fff\n");
        sb.append("    classDef field fill:#ffd43b,stroke:#e67700,color:#000\n");

        // Declare nodes
        for (String nodeId : nodeIds) {
            CodeGraphNode node = index.getNode(nodeId);
            String safeId = sanitizeId(nodeId);
            String label = node != null ? shortLabel(nodeId) : nodeId;
            String fileLine = node != null && node.getFilePath() != null
                    ? " (" + simpleFileName(node.getFilePath()) + ":" + node.getLineNumber() + ")"
                    : "";
            sb.append("    ").append(safeId).append("[\"").append(esc(label)).append(fileLine).append("\"]\n");
        }

        // Declare edges
        Set<String> edgeSet = new LinkedHashSet<String>();
        for (String[] edge : edges) {
            String from = sanitizeId(edge[0]);
            String to = sanitizeId(edge[1]);
            String edgeLabel = edge[2];
            String key = from + "|" + to + "|" + edgeLabel;
            if (edgeSet.add(key)) {
                String arrow = mermaidArrow(edgeLabel);
                sb.append("    ").append(from).append(arrow).append(to).append("\n");
            }
        }

        // Apply classes
        sb.append("    class ").append(rootSafeId).append(" root\n");
        for (String nodeId : nodeIds) {
            if (nodeId.equals(root.getId())) continue;
            CodeGraphNode node = index.getNode(nodeId);
            if (node != null) {
                String safeId = sanitizeId(nodeId);
                switch (node.getType()) {
                    case METHOD:
                        sb.append("    class ").append(safeId).append(" method\n");
                        break;
                    case CLASS:
                        sb.append("    class ").append(safeId).append(" classNode\n");
                        break;
                    case FIELD:
                        sb.append("    class ").append(safeId).append(" field\n");
                        break;
                }
            }
        }

        return sb.toString();
    }

    private String writeHtmlFile(String rootId, String type, String mermaid) {
        try {
            File dir = new File(outputDir);
            dir.mkdirs();

            String safeName = sanitizeId(rootId) + "_" + type + "_" + System.currentTimeMillis();
            File file = new File(dir, safeName + ".html");

            String title = "Code Graph: " + shortLabel(rootId) + " (" + type + ")";
            String html = buildHtml(title, mermaid);

            OutputStreamWriter writer = new OutputStreamWriter(
                    new FileOutputStream(file), StandardCharsets.UTF_8);
            try {
                writer.write(html);
            } finally {
                writer.close();
            }

            return file.getAbsolutePath();
        } catch (IOException e) {
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
                + "    .dot-root { background: #ff6b6b; }\n"
                + "    .dot-method { background: #4dabf7; }\n"
                + "    .dot-class { background: #69db7c; }\n"
                + "    .dot-field { background: #ffd43b; }\n"
                + "    .controls { padding: 12px 24px; background: #16213e; border-bottom: 1px solid #0f3460;\n"
                + "                display: flex; gap: 8px; align-items: center; }\n"
                + "    .controls button { background: #0f3460; color: #eee; border: 1px solid #1a4080;\n"
                + "                       padding: 6px 14px; border-radius: 4px; cursor: pointer; font-size: 13px; }\n"
                + "    .controls button:hover { background: #1a4080; }\n"
                + "    .controls input { background: #0f3460; color: #eee; border: 1px solid #1a4080;\n"
                + "                      padding: 6px 12px; border-radius: 4px; font-size: 13px; width: 250px; }\n"
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
                + "      <div class=\"legend-item\"><div class=\"legend-dot dot-root\"></div>Root</div>\n"
                + "      <div class=\"legend-item\"><div class=\"legend-dot dot-method\"></div>Method</div>\n"
                + "      <div class=\"legend-item\"><div class=\"legend-dot dot-class\"></div>Class</div>\n"
                + "      <div class=\"legend-item\"><div class=\"legend-dot dot-field\"></div>Field</div>\n"
                + "    </div>\n"
                + "  </div>\n"
                + "  <div class=\"controls\">\n"
                + "    <button onclick=\"zoomIn()\">Zoom +</button>\n"
                + "    <button onclick=\"zoomOut()\">Zoom -</button>\n"
                + "    <button onclick=\"resetZoom()\">Reset</button>\n"
                + "    <input type=\"text\" id=\"search\" placeholder=\"Search node...\" onkeyup=\"highlightNode()\">\n"
                + "    <button onclick=\"exportSVG()\">Export SVG</button>\n"
                + "  </div>\n"
                + "  <div class=\"graph-container\">\n"
                + "    <div class=\"mermaid\">\n"
                + mermaid
                + "    </div>\n"
                + "  </div>\n"
                + "  <div class=\"footer\">Generated by SnapAgent CodeGraph | Mermaid.js</div>\n"
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
                + "    function highlightNode() {\n"
                + "      var q = document.getElementById('search').value.toLowerCase();\n"
                + "      var nodes = document.querySelectorAll('.mermaid .node');\n"
                + "      nodes.forEach(function(n) {\n"
                + "        var text = n.textContent.toLowerCase();\n"
                + "        n.style.opacity = q && text.indexOf(q) === -1 ? '0.2' : '1';\n"
                + "      });\n"
                + "    }\n"
                + "    function exportSVG() {\n"
                + "      var svg = document.querySelector('.mermaid svg');\n"
                + "      if (!svg) return;\n"
                + "      var data = new XMLSerializer().serializeToString(svg);\n"
                + "      var blob = new Blob([data], {type: 'image/svg+xml'});\n"
                + "      var url = URL.createObjectURL(blob);\n"
                + "      var a = document.createElement('a');\n"
                + "      a.href = url; a.download = 'code-graph.svg'; a.click();\n"
                + "      URL.revokeObjectURL(url);\n"
                + "    }\n"
                + "  </script>\n"
                + "</body>\n"
                + "</html>";
    }

    private String sanitizeId(String id) {
        return id.replaceAll("[^a-zA-Z0-9]", "_");
    }

    private String shortLabel(String id) {
        // com.example.Foo#bar(String) -> Foo#bar
        String s = id;
        int hashIdx = s.indexOf('#');
        if (hashIdx >= 0) {
            String classPart = s.substring(0, hashIdx);
            String methodPart = s.substring(hashIdx);
            int lastDot = classPart.lastIndexOf('.');
            String shortClass = lastDot >= 0 ? classPart.substring(lastDot + 1) : classPart;
            int parenIdx = methodPart.indexOf('(');
            String shortMethod = parenIdx >= 0 ? methodPart.substring(0, parenIdx) : methodPart;
            return shortClass + shortMethod;
        }
        int lastDot = s.lastIndexOf('.');
        return lastDot >= 0 ? s.substring(lastDot + 1) : s;
    }

    private String simpleFileName(String filePath) {
        int lastSlash = filePath.lastIndexOf('/');
        return lastSlash >= 0 ? filePath.substring(lastSlash + 1) : filePath;
    }

    private String mermaidArrow(String edgeType) {
        if ("CALLS".equals(edgeType)) return " -->|calls| ";
        if ("EXTENDS".equals(edgeType)) return " -.->|extends| ";
        if ("IMPLEMENTS".equals(edgeType)) return " -.->|implements| ";
        if ("DEPENDS_ON".equals(edgeType)) return " -->|depends| ";
        if ("OVERRIDES".equals(edgeType)) return " -.->|overrides| ";
        if ("REFERENCES".equals(edgeType)) return " -->|refs| ";
        return " --> ";
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private String handleCallChain(String query, int maxDepth) {
        if (query == null || query.isEmpty()) {
            return messages.notFound();
        }
        List<CodeGraphNode> targets = resolveNodes(query);
        if (targets.isEmpty()) {
            return messages.notFoundQuery(query);
        }

        StringBuilder sb = new StringBuilder();
        for (CodeGraphNode target : targets) {
            if (target.getType() != CodeGraphNode.NodeType.METHOD) continue;
            sb.append(messages.forwardCallChain(target.getId())).append("\n");
            List<CodeGraphNode> chain = index.findCallChain(target.getId(), maxDepth);
            if (chain.isEmpty()) {
                sb.append(messages.noDownstreamCalls()).append("\n");
            } else {
                for (int i = 0; i < chain.size(); i++) {
                    CodeGraphNode n = chain.get(i);
                    sb.append("  ").append(i + 1).append(". ")
                      .append(n.getId()).append(" (").append(n.getFilePath())
                      .append(":").append(n.getLineNumber()).append(")\n");
                }
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String handleReverseChain(String query, int maxDepth) {
        if (query == null || query.isEmpty()) {
            return messages.notFound();
        }
        List<CodeGraphNode> targets = resolveNodes(query);
        if (targets.isEmpty()) {
            return messages.notFoundQuery(query);
        }

        StringBuilder sb = new StringBuilder();
        for (CodeGraphNode target : targets) {
            if (target.getType() != CodeGraphNode.NodeType.METHOD) continue;
            sb.append(messages.reverseCallChain(target.getId())).append("\n");
            List<CodeGraphNode> chain = index.findReverseCallChain(target.getId(), maxDepth);
            if (chain.isEmpty()) {
                sb.append(messages.noCallers()).append("\n");
            } else {
                for (int i = 0; i < chain.size(); i++) {
                    CodeGraphNode n = chain.get(i);
                    sb.append("  ").append(i + 1).append(". ")
                      .append(n.getId()).append(" (").append(n.getFilePath())
                      .append(":").append(n.getLineNumber()).append(")\n");
                }
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String handleImpactAnalysis(String query, int maxDepth) {
        if (query == null || query.isEmpty()) {
            return messages.notFound();
        }
        List<CodeGraphNode> targets = resolveNodes(query);
        if (targets.isEmpty()) {
            return messages.notFoundQuery(query);
        }

        StringBuilder sb = new StringBuilder();
        for (CodeGraphNode target : targets) {
            sb.append(messages.impactScope(target.getId())).append("\n");
            List<CodeGraphNode> impacted = index.findImpactScope(target.getId(), maxDepth);
            if (impacted.isEmpty()) {
                sb.append(messages.noImpactedNodes()).append("\n");
            } else {
                for (int i = 0; i < impacted.size(); i++) {
                    CodeGraphNode n = impacted.get(i);
                    sb.append("  ").append(i + 1).append(". [").append(nodeTypeLabel(n))
                      .append("] ").append(n.getId())
                      .append(" (").append(n.getFilePath())
                      .append(":").append(n.getLineNumber()).append(")\n");
                }
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String handleFind(String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return messages.notFound();
        }
        List<CodeGraphNode> nodes = index.findByName(pattern);
        if (nodes.isEmpty()) {
            return messages.notFoundQuery(pattern);
        }

        StringBuilder sb = new StringBuilder();
        sb.append(messages.matchingNodes(nodes.size())).append("\n");
        for (int i = 0; i < nodes.size(); i++) {
            CodeGraphNode n = nodes.get(i);
            sb.append(i + 1).append(". [").append(nodeTypeLabel(n)).append("] ")
              .append(n.getId()).append("\n");
            sb.append("   ").append(messages.fileLabel()).append(": ").append(n.getFilePath())
              .append(":").append(n.getLineNumber()).append("\n");
            if (n.getReturnType() != null && !n.getReturnType().isEmpty()) {
                sb.append("   ").append(messages.typeLabel()).append(": ").append(n.getReturnType()).append("\n");
            }
        }
        return sb.toString();
    }

    private String handleCodeView(String query) {
        if (query == null || query.isEmpty()) {
            return messages.notFound();
        }
        List<CodeGraphNode> targets = resolveNodes(query);
        if (targets.isEmpty()) {
            return messages.notFoundQuery(query);
        }

        StringBuilder sb = new StringBuilder();
        for (CodeGraphNode node : targets) {
            sb.append(messages.sourceExcerpt(node.getId())).append("\n");
            String sourceCode = node.getSourceCode();
            if (sourceCode == null || sourceCode.isEmpty()) {
                sb.append("  ").append(messages.noSourceExcerpt(node.getId())).append("\n");
            } else {
                sb.append(sourceCode).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * Resolves a query string to matching nodes (by ID, name, or fuzzy match).
     */
    private List<CodeGraphNode> resolveNodes(String query) {
        List<CodeGraphNode> results = new java.util.ArrayList<CodeGraphNode>();
        // Try exact ID match first
        CodeGraphNode exact = index.getNode(query);
        if (exact != null) {
            results.add(exact);
            return results;
        }
        // Try fuzzy name match
        return index.findByName(query);
    }

    private String nodeTypeLabel(CodeGraphNode node) {
        switch (node.getType()) {
            case CLASS: return messages.classLabel();
            case METHOD: return messages.methodLabel();
            case FIELD: return messages.fieldLabel();
            default: return "?";
        }
    }
}
