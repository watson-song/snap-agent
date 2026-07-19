package cn.watsontech.snapagent.core.knowledge;

import java.util.List;

/**
 * 知识源 SPI。宿主可实现此接口对接 Confluence / 语雀 / 自定义源。
 *
 * <p>v0.7 provides {@code MarkdownKnowledgeSource} (starter layer) as the
 * default implementation. Custom implementations (e.g. external API, database)
 * can be contributed as Spring beans and will be collected by the
 * auto-configuration into {@link KnowledgeBase}.</p>
 */
public interface KnowledgeSource {

    /**
     * Load all knowledge fragments from this source.
     *
     * @return list of fragments (may be empty, never null)
     */
    List<KnowledgeFragment> load();

    /**
     * Reload all fragments (called during hot-reload).
     */
    void reload();

    /**
     * Source type identifier (e.g. "markdown", "external-api").
     *
     * @return type string used for logging and diagnostics
     */
    String type();
}
