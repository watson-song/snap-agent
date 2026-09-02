package cn.watsontech.snapagent.core.skill;

import java.util.Collections;
import java.util.List;

/**
 * A skill template in the marketplace catalog.
 *
 * <p>Templates are pre-built skill definitions that users can install into their
 * upload directory. Each template wraps a skill definition with marketplace metadata:
 * category, tags, author, and an icon for UI display.</p>
 *
 * <p>Templates are loaded from a catalog (JSON or classpath resources) and displayed
 * in the built-in UI's "Marketplace" tab. Installing a template copies its content
 * to the upload directory, making it available as a regular skill.</p>
 */
public final class SkillTemplate {

    private final String name;
    private final String description;
    private final String category;
    private final List<String> tags;
    private final String icon;
    private final String author;
    private final String version;
    private final String content;
    private final List<String> tools;
    private final List<InputSpec> inputs;
    private final boolean installed;

    public SkillTemplate(String name, String description, String category,
                         List<String> tags, String icon, String author,
                         String version, String content,
                         List<String> tools, List<InputSpec> inputs,
                         boolean installed) {
        this.name = name;
        this.description = description;
        this.category = category;
        this.tags = tags != null ? Collections.unmodifiableList(tags) : Collections.<String>emptyList();
        this.icon = icon != null ? icon : "📋";
        this.author = author != null ? author : "SnapAgent";
        this.version = version != null ? version : "1.0";
        this.content = content;
        this.tools = tools != null ? Collections.unmodifiableList(tools) : Collections.<String>emptyList();
        this.inputs = inputs != null ? Collections.unmodifiableList(inputs) : Collections.<InputSpec>emptyList();
        this.installed = installed;
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getCategory() { return category; }
    public List<String> getTags() { return tags; }
    public String getIcon() { return icon; }
    public String getAuthor() { return author; }
    public String getVersion() { return version; }
    public String getContent() { return content; }
    public List<String> getTools() { return tools; }
    public List<InputSpec> getInputs() { return inputs; }
    public boolean isInstalled() { return installed; }

    /**
     * Returns a copy of this template with the installed flag set to the given value.
     */
    public SkillTemplate withInstalled(boolean installed) {
        return new SkillTemplate(name, description, category, tags, icon, author,
                version, content, tools, inputs, installed);
    }
}
