package cn.watsontech.snapagent.boot2x.codegraph;

import cn.watsontech.snapagent.core.codegraph.CodeGraphIndex;
import cn.watsontech.snapagent.core.codegraph.CodeGraphNode;
import cn.watsontech.snapagent.core.tool.Tool;
import cn.watsontech.snapagent.core.tool.ToolParam;

import java.util.List;

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
 * <p>Provides four tools for navigating code relationships:</p>
 * <ul>
 *   <li>{@code call_chain} — forward call chain (A→B→C)</li>
 *   <li>{@code reverse_chain} — reverse call chain (who calls X)</li>
 *   <li>{@code impact_analysis} — change impact scope</li>
 *   <li>{@code find} — name-based node lookup</li>
 * </ul>
 */
public class CodeGraphTools {

    private final CodeGraphIndex index;
    private final int defaultMaxDepth;
    private final int defaultMaxImpactDepth;
    private final CodeGraphMessages messages;

    public CodeGraphTools(CodeGraphIndex index, int defaultMaxDepth, int defaultMaxImpactDepth) {
        this(index, defaultMaxDepth, defaultMaxImpactDepth, new ChineseCodeGraphMessages());
    }

    public CodeGraphTools(CodeGraphIndex index, int defaultMaxDepth, int defaultMaxImpactDepth,
                         CodeGraphMessages messages) {
        this.index = index;
        this.defaultMaxDepth = defaultMaxDepth;
        this.defaultMaxImpactDepth = defaultMaxImpactDepth;
        this.messages = messages != null ? messages : new ChineseCodeGraphMessages();
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

    // ---- Internal handlers (reused from 1.x CodeGraphToolProvider) ----

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
