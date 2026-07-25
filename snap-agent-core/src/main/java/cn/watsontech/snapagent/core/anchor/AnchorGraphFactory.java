package cn.watsontech.snapagent.core.anchor;

import cn.watsontech.snapagent.core.graph.CompiledGraph;
import cn.watsontech.snapagent.core.graph.GraphState;
import cn.watsontech.snapagent.core.graph.Node;
import cn.watsontech.snapagent.core.graph.StateGraph;
import cn.watsontech.snapagent.core.graph.execution.ExecutionContext;
import cn.watsontech.snapagent.core.graph.hitl.InterruptException;

/**
 * Builds anchor-mode graphs for three scenarios:
 *
 * <ul>
 *   <li><b>auto</b> — delegates to ReActGraphFactory, anchor context as skill body</li>
 *   <li><b>off</b> — linear graph: entry → answer → END, no tools, HtmlOutputConverter</li>
 *   <li><b>inject</b> — linear graph: entry → generate → cache → END, HTML injection</li>
 * </ul>
 *
 * <p>This factory produces the graph topology only — node implementations
 * are injected or created at the starter layer where LlmClient and
 * tool registries are available.</p>
 */
public class AnchorGraphFactory {

    public enum Mode {
        AUTO,
        OFF,
        INJECT
    }

    /**
     * Build an "off" mode linear graph: entry → answer → END.
     *
     * <p>No tools node. LLM answers purely from anchor context.
     * Output is processed through HtmlOutputConverter.</p>
     *
     * @param entryNode  the entry node (sets up system prompt with anchor context)
     * @param answerNode the answer node (calls LLM, applies HtmlOutputConverter)
     * @return compiled linear graph
     */
    public CompiledGraph buildOffMode(Node entryNode, Node answerNode) {
        StateGraph g = new StateGraph();
        g.addNode("entry", entryNode)
            .addNode("answer", answerNode)
            .addNode("END", noopNode("END"))
            .addEdge("entry", "answer")
            .addEdge("answer", "END")
            .setEntryPoint("entry");
        return g.compile();
    }

    /**
     * Build an "inject" mode linear graph: entry → generate → cache → END.
     *
     * <p>Generates HTML fragment, caches it, injects into host page.
     * Output is processed through HtmlOutputConverter with template.</p>
     *
     * @param entryNode    the entry node
     * @param generateNode the generate node (calls LLM, applies HtmlOutputConverter)
     * @param cacheNode    the cache node (writes to AnchorHtmlCache)
     * @return compiled linear graph
     */
    public CompiledGraph buildInjectMode(Node entryNode, Node generateNode, Node cacheNode) {
        StateGraph g = new StateGraph();
        g.addNode("entry", entryNode)
            .addNode("generate", generateNode)
            .addNode("cache", cacheNode)
            .addNode("END", noopNode("END"))
            .addEdge("entry", "generate")
            .addEdge("generate", "cache")
            .addEdge("cache", "END")
            .setEntryPoint("entry");
        return g.compile();
    }

    /**
     * Build an "auto" mode graph that reuses the ReAct topology.
     *
     * <p>The caller provides a pre-compiled ReAct graph (from ReActGraphFactory)
     * with anchor context injected as the skill body.</p>
     *
     * @param reactCompiledGraph the already-compiled ReAct graph
     * @return the same compiled graph (auto mode = ReAct)
     */
    public CompiledGraph buildAutoMode(CompiledGraph reactCompiledGraph) {
        // Auto mode reuses ReAct graph as-is
        return reactCompiledGraph;
    }

    private Node noopNode(String name) {
        return new Node() {
            @Override
            public String getName() { return name; }
            @Override
            public GraphState execute(GraphState state, ExecutionContext ctx) throws InterruptException {
                return state;
            }
        };
    }
}
