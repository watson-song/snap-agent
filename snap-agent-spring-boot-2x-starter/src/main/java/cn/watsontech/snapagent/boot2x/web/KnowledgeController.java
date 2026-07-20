package cn.watsontech.snapagent.boot2x.web;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import cn.watsontech.snapagent.core.knowledge.KnowledgeBase;
import cn.watsontech.snapagent.core.knowledge.KnowledgeFragment;
import cn.watsontech.snapagent.core.knowledge.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST endpoints for the embedded knowledge base (v0.7).
 *
 * <p>Only assembled when {@code snap-agent.knowledge.enabled=true}.
 * Exposes status, search, reload and upload endpoints so the web UI can
 * display knowledge fragments, test search queries, refresh the cache after
 * editing source files, and add new markdown fragments on the fly.</p>
 */
@RestController
@ConditionalOnProperty(prefix = "snap-agent.knowledge", name = "enabled", havingValue = "true")
public class KnowledgeController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeController.class);

    private final KnowledgeBase knowledgeBase;
    private final SnapAgentProperties.Knowledge knowledgeConfig;

    public KnowledgeController(KnowledgeBase knowledgeBase,
                               SnapAgentProperties properties) {
        this.knowledgeBase = knowledgeBase;
        this.knowledgeConfig = properties.getKnowledge();
    }

    @GetMapping("${snap-agent.base-path:/snap-agent}/knowledge/status")
    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("enabled", true);
        result.put("fragmentCount", knowledgeBase.size());
        result.put("maxFragments", knowledgeConfig.getMaxFragments());
        result.put("minScore", knowledgeConfig.getMinScore());
        List<Map<String, Object>> sourceList = new ArrayList<Map<String, Object>>();
        for (SnapAgentProperties.KnowledgeSourceConfig src : knowledgeConfig.getSources()) {
            Map<String, Object> s = new LinkedHashMap<String, Object>();
            s.put("type", src.getType());
            s.put("dir", src.getDir());
            s.put("writable", isWritableDir(src.getDir()));
            sourceList.add(s);
        }
        result.put("sources", sourceList);
        return result;
    }

    @GetMapping("${snap-agent.base-path:/snap-agent}/knowledge/search")
    public Map<String, Object> search(@RequestParam String q) {
        int topK = knowledgeConfig.getMaxFragments();
        double minScore = knowledgeConfig.getMinScore();
        // For UI search, return more results than the injection limit
        int searchTopK = Math.max(topK * 3, 10);
        List<SearchResult> results = knowledgeBase.searchWithScores(q, searchTopK, minScore);

        List<Map<String, Object>> fragmentList = new ArrayList<Map<String, Object>>();
        for (SearchResult sr : results) {
            KnowledgeFragment f = sr.getFragment();
            Map<String, Object> dto = new LinkedHashMap<String, Object>();
            dto.put("title", f.getTitle());
            dto.put("content", f.getContent());
            dto.put("source", f.getSource());
            dto.put("metadata", f.getMetadata());
            dto.put("score", sr.getScore());
            fragmentList.add(dto);
        }

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("query", q);
        result.put("totalFragments", knowledgeBase.size());
        result.put("matched", fragmentList.size());
        result.put("fragments", fragmentList);
        return result;
    }

    /**
     * Lists all cached fragments (unranked). Used by the UI to render the
     * "click stat card → browse all knowledge points" view.
     *
     * <p>Returns each fragment's title, source, metadata and full content
     * (no score — this is a browse endpoint, not a search).</p>
     */
    @GetMapping("${snap-agent.base-path:/snap-agent}/knowledge/fragments")
    public ResponseEntity<Object> listAll() {
        List<KnowledgeFragment> fragments = knowledgeBase.listAll();
        List<Map<String, Object>> fragmentList = new ArrayList<Map<String, Object>>();
        for (KnowledgeFragment f : fragments) {
            Map<String, Object> dto = new LinkedHashMap<String, Object>();
            dto.put("title", f.getTitle());
            dto.put("content", f.getContent());
            dto.put("source", f.getSource());
            dto.put("metadata", f.getMetadata());
            fragmentList.add(dto);
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("total", fragments.size());
        result.put("fragments", fragmentList);
        return ResponseEntity.ok(result);
    }

    // ---- POST /knowledge/reload ----
    @PostMapping("${snap-agent.base-path:/snap-agent}/knowledge/reload")
    public ResponseEntity<Object> reload() {
        try {
            knowledgeBase.reload();
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("reloaded", true);
            result.put("fragmentCount", knowledgeBase.size());
            log.info("Knowledge base reloaded via REST call; fragments={}", knowledgeBase.size());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Knowledge reload failed: {}", e.getMessage(), e);
            Map<String, Object> err = new LinkedHashMap<String, Object>();
            err.put("error", "reload failed: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(err);
        }
    }

    // ---- POST /knowledge/upload ----
    @PostMapping("${snap-agent.base-path:/snap-agent}/knowledge/upload")
    public ResponseEntity<Object> upload(@RequestPart("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("error", "file is empty"));
        }
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".md")) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("error",
                    "only .md files are supported"));
        }

        // Find the first writable (non-classpath) source directory
        Path uploadDir = null;
        for (SnapAgentProperties.KnowledgeSourceConfig src : knowledgeConfig.getSources()) {
            String dir = src.getDir();
            if (dir != null && !dir.isEmpty() && isWritableDir(dir)) {
                uploadDir = Paths.get(dir);
                break;
            }
        }
        if (uploadDir == null) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Collections.singletonMap("error",
                            "no writable knowledge source directory configured; " +
                            "all sources are classpath: only"));
        }

        try {
            if (!Files.isDirectory(uploadDir)) {
                Files.createDirectories(uploadDir);
            }
            Path dest = uploadDir.resolve(filename);
            Files.write(dest, file.getBytes());
            // Reload to pick up the new fragment
            knowledgeBase.reload();
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("filename", filename);
            result.put("savedTo", dest.toString());
            result.put("fragmentCount", knowledgeBase.size());
            log.info("Knowledge file '{}' uploaded to {}", filename, dest);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Knowledge upload failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Collections.singletonMap("error",
                            "upload failed: " + e.getMessage()));
        }
    }

    /** Returns true if the dir is a writable filesystem path (not classpath:). */
    private boolean isWritableDir(String dir) {
        if (dir == null || dir.isEmpty()) {
            return false;
        }
        if (dir.startsWith("classpath:")) {
            return false;
        }
        try {
            Path p = Paths.get(dir);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
