package com.watsontech.snapagent.core.skill;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Parses skill markdown frontmatter into {@link SkillMeta}.
 *
 * <p>A valid skill file starts with a {@code ---} delimiter line, followed by a
 * YAML block, then a closing {@code ---} delimiter, then the skill body.
 * The YAML block is parsed with {@link SafeConstructor} (no arbitrary object
 * construction) to prevent deserialization attacks.</p>
 *
 * <p>Required frontmatter fields: {@code name}, {@code description}, {@code tools}.
 * Optional: {@code inputs} (list of input specs).</p>
 *
 * <p>The returned {@link SkillMeta} has availability {@code AVAILABLE} if the
 * frontmatter is well-formed, or {@code INVALID} if any validation fails.
 * Tools-contract validation (AVAILABLE vs UNAVAILABLE) is performed later by
 * {@link SkillRegistry}.</p>
 */
public class SkillLoader {

    private static final String DELIMITER = "---";

    private final Yaml yaml;

    public SkillLoader() {
        this.yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
    }

    /**
     * Parse skill content (the full .md file text) into a {@link SkillMeta}.
     *
     * @param content the raw skill file content
     * @return parsed metadata; never {@code null}
     */
    public SkillMeta parse(String content) {
        if (content == null || content.isEmpty()) {
            return invalid(null, "empty content");
        }

        // Split into lines for delimiter detection
        String[] lines = content.split("\n", -1);
        if (lines.length == 0 || !DELIMITER.equals(lines[0].trim())) {
            return invalid(null, "frontmatter delimiter missing on first line");
        }

        // Find the closing delimiter
        int closingLine = -1;
        for (int i = 1; i < lines.length; i++) {
            if (DELIMITER.equals(lines[i].trim())) {
                closingLine = i;
                break;
            }
        }
        if (closingLine == -1) {
            return invalid(null, "frontmatter closing delimiter not found");
        }

        // Extract YAML block (with auto-quoting for values containing colons)
        StringBuilder yamlBlock = new StringBuilder();
        for (int i = 1; i < closingLine; i++) {
            String yamlLine = ensureYamlValueQuoted(lines[i]);
            yamlBlock.append(yamlLine);
            if (i < closingLine - 1) {
                yamlBlock.append("\n");
            }
        }

        // Extract body (everything after closing delimiter)
        StringBuilder body = new StringBuilder();
        for (int i = closingLine + 1; i < lines.length; i++) {
            body.append(lines[i]);
            if (i < lines.length - 1) {
                body.append("\n");
            }
        }

        Map<String, Object> map;
        try {
            Object loaded = yaml.load(yamlBlock.toString());
            if (loaded == null) {
                return invalid(null, "frontmatter is empty");
            }
            if (!(loaded instanceof Map)) {
                return invalid(null, "frontmatter must be a YAML mapping");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> casted = (Map<String, Object>) loaded;
            map = casted;
        } catch (RuntimeException e) {
            return invalid(null, "YAML parse error: " + e.getMessage());
        }

        return buildMeta(map, body.toString());
    }

    private SkillMeta buildMeta(Map<String, Object> map, String body) {
        // Validate name
        Object nameObj = map.get("name");
        if (!(nameObj instanceof String) || ((String) nameObj).trim().isEmpty()) {
            return invalid(extractName(map), "missing required field: name");
        }
        String name = ((String) nameObj).trim();

        // Validate description
        Object descObj = map.get("description");
        if (!(descObj instanceof String) || ((String) descObj).trim().isEmpty()) {
            return invalid(name, "missing required field: description");
        }
        String description = ((String) descObj).trim();

        // Validate tools (optional — pure LLM skills may omit)
        List<String> tools = Collections.emptyList();
        Object toolsObj = map.get("tools");
        if (toolsObj != null) {
            if (!(toolsObj instanceof List)) {
                return invalid(name, "invalid field: tools (must be a list)");
            }
            tools = toStringList((List<?>) toolsObj, "tools");
        }

        // Parse inputs (optional)
        List<InputSpec> inputs = Collections.emptyList();
        Object inputsObj = map.get("inputs");
        if (inputsObj != null) {
            if (!(inputsObj instanceof List)) {
                return invalid(name, "inputs must be a list");
            }
            inputs = parseInputs((List<?>) inputsObj, name);
            if (inputs == null) {
                // parseInputs already returned an invalid meta via exception path;
                // but we handle it inline
                return invalid(name, "invalid input spec: missing key");
            }
        }

        // Parse shortcuts (optional)
        List<Shortcut> shortcuts = Collections.emptyList();
        Object shortcutsObj = map.get("shortcuts");
        if (shortcutsObj != null) {
            if (!(shortcutsObj instanceof List)) {
                return invalid(name, "shortcuts must be a list");
            }
            shortcuts = parseShortcuts((List<?>) shortcutsObj, name);
            if (shortcuts == null) {
                return invalid(name, "invalid shortcut spec: missing label or message");
            }
        }

        return new SkillMeta(name, description, tools, inputs, shortcuts, body,
                SkillAvailability.AVAILABLE, null, "custom", false);
    }

    @SuppressWarnings("unchecked")
    private List<InputSpec> parseInputs(List<?> rawList, String skillName) {
        List<InputSpec> result = new ArrayList<>();
        for (Object item : rawList) {
            if (!(item instanceof Map)) {
                return null;
            }
            Map<String, Object> im = (Map<String, Object>) item;
            Object keyObj = im.get("key");
            if (!(keyObj instanceof String) || ((String) keyObj).trim().isEmpty()) {
                return null;
            }
            String key = (String) keyObj;
            String label = im.get("label") instanceof String ? (String) im.get("label") : key;
            boolean required = toBoolean(im.get("required"));
            String type = im.get("type") instanceof String ? (String) im.get("type") : "string";
            List<String> options = Collections.emptyList();
            if (im.get("options") instanceof List) {
                options = toStringList((List<?>) im.get("options"), "options");
            }
            String defaultValue = null;
            Object defObj = im.get("default");
            if (defObj != null) {
                defaultValue = String.valueOf(defObj);
            }
            result.add(new InputSpec(key, label, required, type, options, defaultValue));
        }
        return result;
    }

    private List<Shortcut> parseShortcuts(List<?> rawList, String skillName) {
        List<Shortcut> result = new ArrayList<>();
        for (Object item : rawList) {
            if (!(item instanceof Map)) {
                return null;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> sm = (Map<String, Object>) item;
            Object labelObj = sm.get("label");
            Object messageObj = sm.get("message");
            if (!(labelObj instanceof String) || !(messageObj instanceof String)) {
                return null;
            }
            result.add(new Shortcut((String) labelObj, (String) messageObj));
        }
        return result;
    }

    private List<String> toStringList(List<?> raw, String fieldName) {
        List<String> result = new ArrayList<>();
        for (Object o : raw) {
            if (o == null) {
                continue;
            }
            result.add(String.valueOf(o));
        }
        return result;
    }

    private boolean toBoolean(Object obj) {
        if (obj instanceof Boolean) {
            return (Boolean) obj;
        }
        if (obj instanceof String) {
            return Boolean.parseBoolean((String) obj);
        }
        return false;
    }

    private String extractName(Map<String, Object> map) {
        Object nameObj = map.get("name");
        return nameObj instanceof String ? (String) nameObj : null;
    }

    /**
     * Auto-quotes YAML scalar values that contain ": " (colon+space) which
     * would otherwise be parsed as a nested mapping and cause a parse error.
     * Skips lines that are already quoted, are list items, or have no value.
     */
    private String ensureYamlValueQuoted(String line) {
        if (line == null || line.isEmpty()) return line;
        String trimmed = line.trim();
        if (trimmed.startsWith("#") || trimmed.startsWith("-")) return line;

        // Find the first "key:" separator
        int colonIdx = -1;
        for (int i = 0; i < trimmed.length() - 1; i++) {
            if (trimmed.charAt(i) == ':' && trimmed.charAt(i + 1) == ' ') {
                colonIdx = i;
                break;
            }
        }
        if (colonIdx < 0) return line;

        // Extract key and value (preserving original indentation)
        int valueStart = line.indexOf(':', colonIdx) + 2; // after ": "
        while (valueStart < line.length() && line.charAt(valueStart) == ' ') {
            valueStart++;
        }
        if (valueStart >= line.length()) return line; // empty value

        String value = line.substring(valueStart).trim();
        // Already quoted?
        if (value.startsWith("\"") || value.startsWith("'")) return line;
        // Contains ": " in the value? If so, quote it.
        if (value.contains(": ") || value.endsWith(":")) {
            String escaped = value.replace("\"", "\\\"");
            return line.substring(0, valueStart) + "\"" + escaped + "\"";
        }
        return line;
    }

    private SkillMeta invalid(String name, String reason) {
        return new SkillMeta(name, null, Collections.<String>emptyList(),
                Collections.<InputSpec>emptyList(), Collections.<Shortcut>emptyList(),
                null, SkillAvailability.INVALID, reason, "custom", false);
    }
}
