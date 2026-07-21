package cn.watsontech.snapagent.boot2x.anchor;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Carries the page-section context from client to server.
 *
 * <p>Includes the anchor name, content (Markdown-converted by Turndown on client),
 * page URL, truncation flag, original length, and optional meta info (headings,
 * table count, code block count).</p>
 *
 * <p>Used in {@code POST /snap-agent/runs} request body as the {@code anchor} field
 * and in {@code POST /snap-agent/anchor/preprocess} request body.</p>
 */
public class AnchorContext {

    private final String name;
    private final String content;
    private final boolean truncated;
    private final long originalLength;
    private final Map<String, Object> meta;
    private final String pageUrl;

    /** Full constructor. */
    public AnchorContext(String name, String content, boolean truncated,
                        long originalLength, Map<String, Object> meta, String pageUrl) {
        this.name = name;
        this.content = content;
        this.truncated = truncated;
        this.originalLength = originalLength;
        this.meta = meta == null ? Collections.<String, Object>emptyMap() : new LinkedHashMap<>(meta);
        this.pageUrl = pageUrl;
    }

    /** Minimal constructor — no truncation, no meta. */
    public AnchorContext(String name, String content, String pageUrl) {
        this(name, content, false, 0, null, pageUrl);
    }

    /**
     * Parse from a Map (typically deserialized from JSON request body).
     * Returns null if input is null or required fields (name + content) are missing.
     */
    @SuppressWarnings("unchecked")
    public static AnchorContext fromMap(Map<String, Object> map) {
        if (map == null) return null;

        Object nameObj = map.get("name");
        Object contentObj = map.get("content");
        if (!(nameObj instanceof String) || !(contentObj instanceof String)) {
            return null;
        }
        String name = (String) nameObj;
        String content = (String) contentObj;
        if (name.isEmpty() || content.isEmpty()) {
            return null;
        }

        boolean truncated = Boolean.TRUE.equals(map.get("truncated"));
        long originalLength = 0;
        Object lenObj = map.get("originalLength");
        if (lenObj instanceof Number) {
            originalLength = ((Number) lenObj).longValue();
        }
        Map<String, Object> meta = null;
        Object metaObj = map.get("meta");
        if (metaObj instanceof Map) {
            meta = (Map<String, Object>) metaObj;
        }
        String pageUrl = null;
        Object urlObj = map.get("pageUrl");
        if (urlObj instanceof String) {
            pageUrl = (String) urlObj;
        }

        return new AnchorContext(name, content, truncated, originalLength, meta, pageUrl);
    }

    /**
     * Build an augmented user message that combines the user's question with
     * the anchor context, suitable for sending to the LLM.
     */
    public String augmentMessage(String userQuestion) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户正在浏览页面 \"").append(pageUrl != null ? pageUrl : "(unknown)").append("\"");
        sb.append(" 的 \"").append(name).append("\" 区块。\n\n");
        if (truncated) {
            sb.append("（内容已截断，原始长度 ").append(originalLength).append(" 字符）\n");
            if (!meta.isEmpty()) {
                sb.append("元信息：");
                for (Map.Entry<String, Object> e : meta.entrySet()) {
                    sb.append(e.getKey()).append("=").append(e.getValue()).append(" ");
                }
                sb.append("\n");
            }
            sb.append("\n");
        }
        sb.append("区块内容：\n").append(content).append("\n\n");
        sb.append("用户提问：").append(userQuestion);
        return sb.toString();
    }

    /** Returns true if content is long enough to warrant summarization. */
    public boolean needsSummary(int thresholdChars) {
        return content != null && content.length() > thresholdChars;
    }

    public String getName() { return name; }
    public String getContent() { return content; }
    public boolean isTruncated() { return truncated; }
    public long getOriginalLength() { return originalLength; }
    public Map<String, Object> getMeta() { return meta; }
    public String getPageUrl() { return pageUrl; }
}
