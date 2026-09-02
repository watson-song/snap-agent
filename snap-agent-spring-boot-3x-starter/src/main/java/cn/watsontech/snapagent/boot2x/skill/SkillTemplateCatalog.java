package cn.watsontech.snapagent.boot2x.skill;

import cn.watsontech.snapagent.core.skill.InputSpec;
import cn.watsontech.snapagent.core.skill.SkillMeta;
import cn.watsontech.snapagent.core.skill.SkillRegistry;
import cn.watsontech.snapagent.core.skill.SkillTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Catalog of installable skill templates (the "Skill Marketplace").
 *
 * <p>Templates are loaded from a JSON catalog file on the classpath
 * ({@code classpath:/snap-agent/skill-templates.json}). The catalog includes
 * metadata like category, tags, icon, and the skill content itself.</p>
 *
 * <p>Installing a template writes its content as a {@code .md} file to the
 * upload directory, where {@link SkillRegistry} picks it up on next refresh.</p>
 */
public class SkillTemplateCatalog {

    private static final Logger log = LoggerFactory.getLogger(SkillTemplateCatalog.class);
    private static final String CATALOG_RESOURCE = "/snap-agent/skill-templates.json";

    private final Path uploadDir;
    private final SkillRegistry skillRegistry;
    private List<SkillTemplate> templates;

    public SkillTemplateCatalog(Path uploadDir, SkillRegistry skillRegistry) {
        if (uploadDir == null) {
            throw new IllegalArgumentException("uploadDir must not be null");
        }
        if (skillRegistry == null) {
            throw new IllegalArgumentException("skillRegistry must not be null");
        }
        this.uploadDir = uploadDir;
        this.skillRegistry = skillRegistry;
    }

    /**
     * List all available templates, with installed status resolved against current skills.
     */
    public List<SkillTemplate> listTemplates() {
        ensureLoaded();
        Set<String> installedNames = getInstalledSkillNames();
        List<SkillTemplate> result = new ArrayList<SkillTemplate>();
        for (SkillTemplate t : templates) {
            result.add(t.withInstalled(installedNames.contains(t.getName())));
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Get a single template by name, or null if not found.
     */
    public SkillTemplate getTemplate(String name) {
        ensureLoaded();
        Set<String> installedNames = getInstalledSkillNames();
        for (SkillTemplate t : templates) {
            if (t.getName().equals(name)) {
                return t.withInstalled(installedNames.contains(t.getName()));
            }
        }
        return null;
    }

    /**
     * Install a template by writing its content to the upload directory.
     *
     * @return true if installed successfully, false if template not found
     */
    public boolean install(String templateName) {
        ensureLoaded();
        SkillTemplate template = null;
        for (SkillTemplate t : templates) {
            if (t.getName().equals(templateName)) {
                template = t;
                break;
            }
        }
        if (template == null) {
            return false;
        }
        if (template.getContent() == null || template.getContent().isEmpty()) {
            log.warn("Template '{}' has no content to install", templateName);
            return false;
        }

        try {
            Files.createDirectories(uploadDir);
            Path target = uploadDir.resolve(templateName + ".md");
            Files.write(target, template.getContent().getBytes(StandardCharsets.UTF_8));
            log.info("Installed template '{}' to {}", templateName, target);

            // Refresh registry to pick up the new skill
            skillRegistry.refresh();
            return true;
        } catch (IOException e) {
            log.error("Failed to install template '{}': {}", templateName, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Uninstall a template by removing its file from the upload directory.
     *
     * @return true if uninstalled, false if file doesn't exist
     */
    public boolean uninstall(String templateName) {
        Path target = uploadDir.resolve(templateName + ".md");
        try {
            if (Files.exists(target)) {
                Files.delete(target);
                log.info("Uninstalled template '{}' from {}", templateName, target);
                skillRegistry.refresh();
                return true;
            }
            return false;
        } catch (IOException e) {
            log.error("Failed to uninstall template '{}': {}", templateName, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Get list of distinct categories across all templates.
     */
    public List<String> getCategories() {
        ensureLoaded();
        Set<String> cats = new HashSet<String>();
        for (SkillTemplate t : templates) {
            if (t.getCategory() != null && !t.getCategory().isEmpty()) {
                cats.add(t.getCategory());
            }
        }
        List<String> result = new ArrayList<String>(cats);
        Collections.sort(result);
        return result;
    }

    private Set<String> getInstalledSkillNames() {
        Set<String> names = new HashSet<String>();
        for (SkillMeta meta : skillRegistry.all()) {
            names.add(meta.getName());
        }
        return names;
    }

    private void ensureLoaded() {
        if (templates != null) {
            return;
        }
        synchronized (this) {
            if (templates != null) {
                return;
            }
            templates = loadTemplates();
        }
    }

    private List<SkillTemplate> loadTemplates() {
        InputStream is = getClass().getResourceAsStream(CATALOG_RESOURCE);
        if (is == null) {
            log.warn("Skill template catalog not found: {}", CATALOG_RESOURCE);
            return Collections.emptyList();
        }
        try {
            Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8);
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[1024];
            int n;
            while ((n = reader.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
            return parseCatalog(sb.toString());
        } catch (IOException e) {
            log.error("Failed to load skill template catalog: {}", e.getMessage(), e);
            return Collections.emptyList();
        } finally {
            try { is.close(); } catch (IOException ignored) { }
        }
    }

    /**
     * Minimal JSON array parser for the catalog format. Avoids pulling in Jackson
     * just for catalog loading (keeps core dependency footprint small).
     *
     * <p>Expected format:</p>
     * <pre>
     * [
     *   {
     *     "name": "skill-name",
     *     "description": "...",
     *     "category": "Ops",
     *     "tags": ["log", "error"],
     *     "icon": "🔍",
     *     "author": "SnapAgent",
     *     "version": "1.0",
     *     "content": "---\nname: ...\n---\n# Skill body..."
     *   }
     * ]
     * </pre>
     */
    private List<SkillTemplate> parseCatalog(String json) {
        List<SkillTemplate> result = new ArrayList<SkillTemplate>();
        json = json.trim();
        if (!json.startsWith("[") || !json.endsWith("]")) {
            log.error("Invalid catalog format: expected JSON array");
            return result;
        }
        // Strip outer brackets
        json = json.substring(1, json.length() - 1).trim();
        if (json.isEmpty()) {
            return result;
        }

        // Split on top-level objects (simple approach: split on },{ boundary)
        List<String> objects = splitJsonObjects(json);
        for (String obj : objects) {
            Map<String, Object> fields = parseJsonObject(obj);
            if (fields.isEmpty()) continue;

            String name = str(fields, "name");
            if (name == null || name.isEmpty()) continue;

            String description = str(fields, "description");
            String category = str(fields, "category");
            String icon = str(fields, "icon");
            String author = str(fields, "author");
            String version = str(fields, "version");
            String content = str(fields, "content");

            @SuppressWarnings("unchecked")
            List<String> tags = (List<String>) fields.get("tags");
            if (tags == null) tags = Collections.emptyList();

            result.add(new SkillTemplate(name, description, category, tags,
                    icon, author, version, content,
                    Collections.<String>emptyList(),
                    Collections.<InputSpec>emptyList(),
                    false));
        }
        return result;
    }

    private List<String> splitJsonObjects(String json) {
        List<String> objects = new ArrayList<String>();
        int depth = 0;
        int start = -1;
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\' && inString) { escaped = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;

            if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    objects.add(json.substring(start, i + 1));
                    start = -1;
                }
            }
        }
        return objects;
    }

    private Map<String, Object> parseJsonObject(String obj) {
        Map<String, Object> map = new HashMap<String, Object>();
        obj = obj.trim();
        if (!obj.startsWith("{") || !obj.endsWith("}")) return map;
        obj = obj.substring(1, obj.length() - 1).trim();

        int i = 0;
        while (i < obj.length()) {
            // Skip whitespace and commas
            while (i < obj.length() && (obj.charAt(i) == ',' || Character.isWhitespace(obj.charAt(i)))) i++;
            if (i >= obj.length()) break;

            // Parse key
            if (obj.charAt(i) != '"') break;
            int keyStart = i + 1;
            i = skipString(obj, i + 1);
            if (i >= obj.length()) break;
            String key = obj.substring(keyStart, i);
            i++; // skip closing quote

            // Skip colon
            while (i < obj.length() && (obj.charAt(i) == ':' || Character.isWhitespace(obj.charAt(i)))) i++;
            if (i >= obj.length()) break;

            // Parse value
            char vc = obj.charAt(i);
            if (vc == '"') {
                int valStart = i + 1;
                i = skipString(obj, i + 1);
                String val = unescapeJson(obj.substring(valStart, i));
                map.put(key, val);
                i++;
            } else if (vc == '[') {
                // Parse array of strings
                List<String> arr = new ArrayList<String>();
                int bracketDepth = 1;
                int arrStart = i + 1;
                i++;
                while (i < obj.length() && bracketDepth > 0) {
                    if (obj.charAt(i) == '[') bracketDepth++;
                    else if (obj.charAt(i) == ']') bracketDepth--;
                    i++;
                }
                String arrContent = obj.substring(arrStart, i - 1);
                // Parse string elements
                int j = 0;
                while (j < arrContent.length()) {
                    while (j < arrContent.length() && (arrContent.charAt(j) == ',' || Character.isWhitespace(arrContent.charAt(j)))) j++;
                    if (j >= arrContent.length()) break;
                    if (arrContent.charAt(j) == '"') {
                        int elemStart = j + 1;
                        j = skipString(arrContent, j + 1);
                        arr.add(unescapeJson(arrContent.substring(elemStart, j)));
                        j++;
                    } else {
                        j++;
                    }
                }
                map.put(key, arr);
            } else if (vc == 't' || vc == 'f') {
                // boolean
                if (obj.startsWith("true", i)) { map.put(key, Boolean.TRUE); i += 4; }
                else if (obj.startsWith("false", i)) { map.put(key, Boolean.FALSE); i += 5; }
            } else if (Character.isDigit(vc) || vc == '-') {
                int numStart = i;
                while (i < obj.length() && (Character.isDigit(obj.charAt(i)) || obj.charAt(i) == '.' || obj.charAt(i) == '-')) i++;
                String numStr = obj.substring(numStart, i);
                if (numStr.contains(".")) map.put(key, Double.parseDouble(numStr));
                else map.put(key, Long.parseLong(numStr));
            }
        }
        return map;
    }

    private int skipString(String s, int from) {
        boolean escaped = false;
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\') { escaped = true; continue; }
            if (c == '"') return i;
        }
        return s.length();
    }

    private String unescapeJson(String s) {
        if (s.indexOf('\\') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                switch (next) {
                    case 'n': sb.append('\n'); i++; break;
                    case 't': sb.append('\t'); i++; break;
                    case 'r': sb.append('\r'); i++; break;
                    case '"': sb.append('"'); i++; break;
                    case '\\': sb.append('\\'); i++; break;
                    case '/': sb.append('/'); i++; break;
                    default: sb.append(c); break;
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof String ? (String) v : null;
    }
}
