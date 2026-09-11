package cn.watsontech.snapagent.boot2x.codegraph;

/**
 * English implementation of {@link CodeGraphMessages}.
 *
 * <p>Use when the agent's output language should be English instead of the
 * default Chinese. Pass an instance to the {@link CodeGraphTools} constructor
 * that accepts a {@link CodeGraphMessages} parameter.</p>
 *
 * <p>P2-18: English translations of the original Chinese strings.</p>
 */
public class EnglishCodeGraphMessages implements CodeGraphMessages {

    @Override
    public String notFound() {
        return "No matching node found.";
    }

    @Override
    public String notFoundQuery(String query) {
        return "No node found matching '" + query + "'.";
    }

    @Override
    public String forwardCallChain(String nodeId) {
        return "Forward call chain (" + nodeId + "):";
    }

    @Override
    public String noDownstreamCalls() {
        return "  (no downstream calls)";
    }

    @Override
    public String reverseCallChain(String nodeId) {
        return "Reverse call chain (who calls " + nodeId + "):";
    }

    @Override
    public String noCallers() {
        return "  (no callers)";
    }

    @Override
    public String impactScope(String nodeId) {
        return "Change impact scope (" + nodeId + "):";
    }

    @Override
    public String noImpactedNodes() {
        return "  (no impacted nodes)";
    }

    @Override
    public String matchingNodes(int count) {
        return "Matching nodes (" + count + "):";
    }

    @Override
    public String classLabel() {
        return "Class";
    }

    @Override
    public String methodLabel() {
        return "Method";
    }

    @Override
    public String fieldLabel() {
        return "Field";
    }

    @Override
    public String fileLabel() {
        return "File";
    }

    @Override
    public String typeLabel() {
        return "Type";
    }

    @Override
    public String sourceExcerpt(String nodeId) {
        return "Key source excerpt (" + nodeId + "):";
    }

    @Override
    public String noSourceExcerpt(String nodeId) {
        return "Node " + nodeId + " has no stored source excerpt "
                + "(built by an older graph, or a runtime without sources).";
    }
}
