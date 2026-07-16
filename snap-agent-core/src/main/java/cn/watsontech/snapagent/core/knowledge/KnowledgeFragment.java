package cn.watsontech.snapagent.core.knowledge;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 知识片段，由 {@link KnowledgeSource} 产生，被 {@link KnowledgeSearcher} 评分。
 *
 * <p>Immutable value object: title, content, source and metadata are fixed at
 * construction time. The metadata map is defensively copied on both input and
 * output to prevent external mutation.</p>
 */
public final class KnowledgeFragment {

    private final String title;
    private final String content;
    private final String source;
    private final Map<String, String> metadata;

    /**
     * Construct an immutable knowledge fragment.
     *
     * @param title    片段标题 (e.g. "补货策略生成规则")
     * @param content  片段内容 (Markdown 正文)
     * @param source   来源标识 (e.g. "business-overview.md:section-3")
     * @param metadata 附加元数据 (tags, category, etc.); null → empty map
     */
    public KnowledgeFragment(String title, String content, String source,
                             Map<String, String> metadata) {
        this.title = title;
        this.content = content;
        this.source = source;
        this.metadata = metadata == null
                ? new LinkedHashMap<String, String>()
                : new LinkedHashMap<String, String>(metadata);
    }

    public String getTitle() {
        return title;
    }

    public String getContent() {
        return content;
    }

    public String getSource() {
        return source;
    }

    /**
     * Returns an unmodifiable view of the metadata map.
     *
     * @return metadata map (never null, empty if none provided)
     */
    public Map<String, String> getMetadata() {
        return Collections.unmodifiableMap(metadata);
    }

    @Override
    public String toString() {
        return "KnowledgeFragment{title='" + title + "', source='" + source
                + "', content=" + (content != null ? content.length() + " chars" : "null")
 + "}";
    }
}
