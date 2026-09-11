package cn.watsontech.snapagent.boot2x.codegraph;

/**
 * Chinese (zh-CN) implementation of {@link CodeGraphMessages}.
 *
 * <p>Default locale for {@link CodeGraphTools}. All strings are the same as
 * the original hard-coded values, ensuring backward compatibility.</p>
 *
 * <p>P2-18: extracted from CodeGraphTools to allow i18n.</p>
 */
public class ChineseCodeGraphMessages implements CodeGraphMessages {

    @Override
    public String notFound() {
        return "未找到匹配的节点。";
    }

    @Override
    public String notFoundQuery(String query) {
        return "未找到匹配 '" + query + "' 的节点。";
    }

    @Override
    public String forwardCallChain(String nodeId) {
        return "正向调用链 (" + nodeId + "):";
    }

    @Override
    public String noDownstreamCalls() {
        return "  (无下游调用)";
    }

    @Override
    public String reverseCallChain(String nodeId) {
        return "反向调用链 (谁调用了 " + nodeId + "):";
    }

    @Override
    public String noCallers() {
        return "  (无调用方)";
    }

    @Override
    public String impactScope(String nodeId) {
        return "变更影响范围 (" + nodeId + "):";
    }

    @Override
    public String noImpactedNodes() {
        return "  (无受影响节点)";
    }

    @Override
    public String matchingNodes(int count) {
        return "匹配节点 (" + count + " 个):";
    }

    @Override
    public String classLabel() {
        return "类";
    }

    @Override
    public String methodLabel() {
        return "方法";
    }

    @Override
    public String fieldLabel() {
        return "字段";
    }

    @Override
    public String fileLabel() {
        return "文件";
    }

    @Override
    public String typeLabel() {
        return "类型";
    }

    @Override
    public String sourceExcerpt(String nodeId) {
        return "关键代码片段 (" + nodeId + "):";
    }

    @Override
    public String noSourceExcerpt(String nodeId) {
        return "节点 " + nodeId + " 未存储代码片段。该节点可能由旧版图谱构建，或为无源码的运行时环境。";
    }
}
