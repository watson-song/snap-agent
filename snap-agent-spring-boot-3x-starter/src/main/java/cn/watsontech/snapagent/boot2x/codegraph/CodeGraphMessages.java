package cn.watsontech.snapagent.boot2x.codegraph;

/**
 * i18n message SPI for {@link CodeGraphTools} output strings.
 *
 * <p>Implementations provide localized labels for the four code-graph tools
 * ({@code call_chain}, {@code reverse_chain}, {@code impact_analysis},
 * {@code find}). The default locale is Chinese; an English implementation
 * is provided for non-Chinese environments.</p>
 *
 * <p>P2-18: extracted from hard-coded Chinese strings so that output can be
 * localized without touching tool logic.</p>
 */
public interface CodeGraphMessages {

    /** Returned when the query is null/empty or no node matches at all. */
    String notFound();

    /** Returned when the query resolves to zero nodes. */
    String notFoundQuery(String query);

    /** Header for the forward call-chain section, e.g. "Forward call chain (nodeId):". */
    String forwardCallChain(String nodeId);

    /** Placeholder when a node has no downstream calls. */
    String noDownstreamCalls();

    /** Header for the reverse call-chain section, e.g. "Reverse call chain (who calls nodeId):". */
    String reverseCallChain(String nodeId);

    /** Placeholder when a node has no callers. */
    String noCallers();

    /** Header for the impact-analysis section, e.g. "Change impact scope (nodeId):". */
    String impactScope(String nodeId);

    /** Placeholder when no nodes are impacted. */
    String noImpactedNodes();

    /** Header for the find section, e.g. "Matching nodes (3):". */
    String matchingNodes(int count);

    /** Label for a CLASS node type. */
    String classLabel();

    /** Label for a METHOD node type. */
    String methodLabel();

    /** Label for a FIELD node type. */
    String fieldLabel();

    /** Label preceding the file path in find output. */
    String fileLabel();

    /** Label preceding the return type in find output. */
    String typeLabel();

    /** Header for the code_view section, e.g. "Source excerpt (nodeId):". */
    String sourceExcerpt(String nodeId);

    /** Hint when a node has no stored source excerpt. */
    String noSourceExcerpt(String nodeId);
}
