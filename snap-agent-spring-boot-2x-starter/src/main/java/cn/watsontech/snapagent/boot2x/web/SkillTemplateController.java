package cn.watsontech.snapagent.boot2x.web;

import cn.watsontech.snapagent.boot2x.skill.SkillTemplateCatalog;
import cn.watsontech.snapagent.core.skill.SkillTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * REST endpoints for the Skill Marketplace (template catalog).
 *
 * <p>Provides endpoints to browse, install, and uninstall skill templates.
 * Templates are pre-built skill definitions that can be installed into the
 * upload directory for customization.</p>
 */
@RestController
@RequestMapping("${snap-agent.base-path:/snap-agent}")
public class SkillTemplateController {

    private static final Logger log = LoggerFactory.getLogger(SkillTemplateController.class);

    private final SkillTemplateCatalog catalog;

    public SkillTemplateController(SkillTemplateCatalog catalog) {
        if (catalog == null) {
            throw new IllegalArgumentException("SkillTemplateCatalog must not be null");
        }
        this.catalog = catalog;
    }

    /**
     * GET /templates — list all available templates with installed status.
     */
    @GetMapping("/templates")
    public ResponseEntity<Object> listTemplates(
            @RequestParam(required = false) String category) {

        List<SkillTemplate> templates = catalog.listTemplates();

        if (category != null && !category.isEmpty()) {
            List<SkillTemplate> filtered = new ArrayList<SkillTemplate>();
            for (SkillTemplate t : templates) {
                if (category.equalsIgnoreCase(t.getCategory())) {
                    filtered.add(t);
                }
            }
            templates = filtered;
        }

        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (SkillTemplate t : templates) {
            result.add(toMap(t, false));
        }

        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("templates", result);
        response.put("total", result.size());
        response.put("categories", catalog.getCategories());
        return ResponseEntity.ok(response);
    }

    /**
     * GET /templates/{name} — get a single template with full content.
     */
    @GetMapping("/templates/{name}")
    public ResponseEntity<Object> getTemplate(@PathVariable String name) {
        SkillTemplate template = catalog.getTemplate(name);
        if (template == null) {
            return errorResponse(HttpStatus.NOT_FOUND, "TEMPLATE_NOT_FOUND",
                    "Template '" + name + "' not found");
        }
        return ResponseEntity.ok(toMap(template, true));
    }

    /**
     * POST /templates/{name}/install — install a template to the upload directory.
     */
    @PostMapping("/templates/{name}/install")
    public ResponseEntity<Object> installTemplate(@PathVariable String name) {
        SkillTemplate template = catalog.getTemplate(name);
        if (template == null) {
            return errorResponse(HttpStatus.NOT_FOUND, "TEMPLATE_NOT_FOUND",
                    "Template '" + name + "' not found");
        }
        if (template.isInstalled()) {
            return errorResponse(HttpStatus.CONFLICT, "ALREADY_INSTALLED",
                    "Template '" + name + "' is already installed");
        }

        boolean success = catalog.install(name);
        if (success) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("name", name);
            result.put("installed", true);
            result.put("message", "Template '" + name + "' installed successfully");
            return ResponseEntity.ok(result);
        } else {
            return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "INSTALL_FAILED",
                    "Failed to install template '" + name + "'");
        }
    }

    /**
     * DELETE /templates/{name}/uninstall — remove an installed template.
     */
    @DeleteMapping("/templates/{name}/uninstall")
    public ResponseEntity<Object> uninstallTemplate(@PathVariable String name) {
        boolean success = catalog.uninstall(name);
        if (success) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("name", name);
            result.put("installed", false);
            result.put("message", "Template '" + name + "' uninstalled successfully");
            return ResponseEntity.ok(result);
        } else {
            return errorResponse(HttpStatus.NOT_FOUND, "NOT_INSTALLED",
                    "Template '" + name + "' is not installed or could not be removed");
        }
    }

    private Map<String, Object> toMap(SkillTemplate t, boolean includeContent) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("name", t.getName());
        m.put("description", t.getDescription());
        m.put("category", t.getCategory());
        m.put("tags", t.getTags());
        m.put("icon", t.getIcon());
        m.put("author", t.getAuthor());
        m.put("version", t.getVersion());
        m.put("installed", t.isInstalled());
        if (includeContent) {
            m.put("content", t.getContent());
            m.put("tools", t.getTools());
        }
        return m;
    }

    private ResponseEntity<Object> errorResponse(HttpStatus status, String error, String message) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("error", error);
        body.put("message", message);
        body.put("status", status.value());
        return ResponseEntity.status(status).body((Object) body);
    }
}
