package cn.watsontech.snapagent.boot2x.codegraph;

import cn.watsontech.snapagent.core.codegraph.CodeGraphEdge;
import cn.watsontech.snapagent.core.codegraph.CodeGraphIndex;
import cn.watsontech.snapagent.core.codegraph.CodeGraphNode;
import cn.watsontech.snapagent.core.tool.ToolContext;
import cn.watsontech.snapagent.core.tool.ToolProvider;
import cn.watsontech.snapagent.core.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * {@link ToolProvider} that exposes code graph query tools to the LLM.
 *
 * <p>Provides four tools for navigating code relationships:</p>
 * <ul>
 *   <li>{@code code_graph_call_chain} — forward call chain (A→B→C)</li>
 *   <li>{@code code_graph_reverse_chain} — reverse call chain (who calls X)</li>
 *   <li>{@code code_graph_impact_analysis} — change impact scope</li>
 *   <li>{@code code_graph_find} — name-based node lookup</li>
 * </ul>
 */
public class CodeGraphToolProvider implements ToolProvider {

    private static final Logger log = LoggerFactory.getLogger(CodeGraphToolProvider.class);

    private final CodeGraphIndex index;
    private final int defaultMaxDepth;
    private final int defaultMaxImpactDepth;

    private static final String CALL_CHAIN_SCHEMA = "{\"name\":\"code_graph_call_chain\","
            + "\"description\":\"查询方法的正向调用链（此方法调用了哪些方法，逐层展开）。"
            + "输入方法签名或方法名，返回调用层级树。\","
            + "\"input_schema\":{\"type\":\"object\",\"properties\":{"
            + "\"method_signature\":{\"type\":\"string\",\"description\":\"方法签名，如 'com.example.Foo#bar(String)' 或方法名 'bar'\"},"
            + "\"max_depth\":{\"type\":\"integer\",\"description\":\"最大调用深度（默认5）\",\"default\":5}"
            + "},\"required\":[\"method_signature\"]}}";

    private static final String REVERSE_CHAIN_SCHEMA = "{\"name\":\"code_graph_reverse_chain\","
            + "\"description\":\"查询反向调用链（谁调用了此方法）。"
            + "输入方法签名或方法名，返回所有调用方。\","
            + "\"input_schema\":{\"type\":\"object\",\"properties\":{"
            + "\"method_signature\":{\"type\":\"string\",\"description\":\"方法签名或方法名\"},"
            + "\"max_depth\":{\"type\":\"integer\",\"description\":\"最大反向深度（默认5）\",\"default\":5}"
            + "},\"required\":[\"method_signature\"]}}";

    private static final String IMPACT_SCHEMA = "{\"name\":\"code_graph_impact_analysis\","
            + "\"description\":\"变更影响分析：如果修改指定类或方法，会影响哪些下游代码。"
            + "返回受影响的方法/类列表。\","
            + "\"input_schema\":{\"type\":\"object\",\"properties\":{"
            + "\"node_name\":{\"type\":\"string\",\"description\":\"类名或方法签名，如 'InventoryService' 或 'com.example.Foo#check()'\"},"
            + "\"max_depth\":{\"type\":\"integer\",\"description\":\"最大影响深度（默认3）\",\"default\":3}"
            + "},\"required\":[\"node_name\"]}}";

    private static final String FIND_SCHEMA = "{\"name\":\"code_graph_find\","
            + "\"description\":\"按名称模糊查找代码节点（类/方法/字段）。"
            + "返回匹配的节点列表，包含文件位置。\","
            + "\"input_schema\":{\"type\":\"object\",\"properties\":{"
            + "\"pattern\":{\"type\":\"string\",\"description\":\"名称模式（模糊匹配，不区分大小写）\"}"
            + "},\"required\":[\"pattern\"]}}";

    private static final String COMBINED_SCHEMA = "{\"name\":\"code_graph_tools\","
            + "\"description\":\"代码图谱查询工具集\","
            + "\"input_schema\":{\"type\":\"object\",\"properties\":{"
            + "\"tool\":{\"type\":\"string\",\"enum\":[\"call_chain\",\"reverse_chain\",\"impact_analysis\",\"find\"],"
            + "\"description\":\"要执行的查询类型\"},"
            + "\"query\":{\"type\":\"string\",\"description\":\"查询参数（方法签名/类名/模式）\"},"
            + "\"max_depth\":{\"type\":\"integer\",\"description\":\"最大深度（可选）\"}"
            + "},\"required\":[\"tool\",\"query\"]}}";

    public CodeGraphToolProvider(CodeGraphIndex index, int defaultMaxDepth, int defaultMaxImpactDepth) {
        this.index = index;
        this.defaultMaxDepth = defaultMaxDepth;
        this.defaultMaxImpactDepth = defaultMaxImpactDepth;
    }

    public CodeGraphToolProvider(CodeGraphIndex index) {
        this(index, 5, 3);
    }

    @Override
    public String name() {
        return "code_graph_tools";
    }

    @Override
    public String schema() {
        return COMBINED_SCHEMA;
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
        long start = System.currentTimeMillis();

        String tool = extractString(args, "tool");
        String query = extractString(args, "query");
        int maxDepth = extractInt(args, "max_depth",
                "impact_analysis".equals(tool) ? defaultMaxImpactDepth : defaultMaxDepth);

        if (tool == null || tool.isEmpty()) {
            return ToolResult.error("missing required parameter: tool", elapsed(start));
        }
        if (query == null || query.isEmpty()) {
            return ToolResult.error("missing required parameter: query", elapsed(start));
        }

        String result;
        try {
            switch (tool) {
                case "call_chain":
                    result = handleCallChain(query, maxDepth);
                    break;
                case "reverse_chain":
                    result = handleReverseChain(query, maxDepth);
                    break;
                case "impact_analysis":
                    result = handleImpactAnalysis(query, maxDepth);
                    break;
                case "find":
                    result = handleFind(query);
                    break;
                default:
                    return ToolResult.error("unknown tool: " + tool, elapsed(start));
            }
        } catch (Exception e) {
            log.error("Code graph tool '{}' failed: {}", tool, e.getMessage());
            return ToolResult.error("Code graph query failed: " + e.getMessage(), elapsed(start));
        }

        if (result == null || result.isEmpty()) {
            result = "无匹配结果。请尝试调整查询条件。";
        }

        return ToolResult.success(result, 0, elapsed(start));
    }

    private String handleCallChain(String query, int maxDepth) {
        List<CodeGraphNode> targets = resolveNodes(query);
        if (targets.isEmpty()) {
            return "未找到匹配 '" + query + "' 的方法节点。";
        }

        StringBuilder sb = new StringBuilder();
        for (CodeGraphNode target : targets) {
            if (target.getType() != CodeGraphNode.NodeType.METHOD) continue;
            sb.append("正向调用链 (").append(target.getId()).append("):\n");
            List<CodeGraphNode> chain = index.findCallChain(target.getId(), maxDepth);
            if (chain.isEmpty()) {
                sb.append("  (无下游调用)\n");
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
        List<CodeGraphNode> targets = resolveNodes(query);
        if (targets.isEmpty()) {
            return "未找到匹配 '" + query + "' 的方法节点。";
        }

        StringBuilder sb = new StringBuilder();
        for (CodeGraphNode target : targets) {
            if (target.getType() != CodeGraphNode.NodeType.METHOD) continue;
            sb.append("反向调用链 (谁调用了 ").append(target.getId()).append("):\n");
            List<CodeGraphNode> chain = index.findReverseCallChain(target.getId(), maxDepth);
            if (chain.isEmpty()) {
                sb.append("  (无调用方)\n");
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
        List<CodeGraphNode> targets = resolveNodes(query);
        if (targets.isEmpty()) {
            return "未找到匹配 '" + query + "' 的节点。";
        }

        StringBuilder sb = new StringBuilder();
        for (CodeGraphNode target : targets) {
            sb.append("变更影响范围 (").append(target.getId()).append("):\n");
            List<CodeGraphNode> impacted = index.findImpactScope(target.getId(), maxDepth);
            if (impacted.isEmpty()) {
                sb.append("  (无受影响节点)\n");
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
        List<CodeGraphNode> nodes = index.findByName(pattern);
        if (nodes.isEmpty()) {
            return "未找到名称匹配 '" + pattern + "' 的节点。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("匹配节点 (").append(nodes.size()).append(" 个):\n");
        for (int i = 0; i < nodes.size(); i++) {
            CodeGraphNode n = nodes.get(i);
            sb.append(i + 1).append(". [").append(nodeTypeLabel(n)).append("] ")
              .append(n.getId()).append("\n");
            sb.append("   文件: ").append(n.getFilePath())
              .append(":").append(n.getLineNumber()).append("\n");
            if (n.getReturnType() != null && !n.getReturnType().isEmpty()) {
                sb.append("   类型: ").append(n.getReturnType()).append("\n");
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
            case CLASS: return "类";
            case METHOD: return "方法";
            case FIELD: return "字段";
            default: return "?";
        }
    }

    private String extractString(Map<String, Object> args, String key) {
        Object val = args.get(key);
        return val != null ? val.toString() : null;
    }

    private int extractInt(Map<String, Object> args, String key, int defaultValue) {
        Object val = args.get(key);
        if (val == null) return defaultValue;
        if (val instanceof Number) {
            return ((Number) val).intValue();
        }
        try {
            return Integer.parseInt(val.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }
}
