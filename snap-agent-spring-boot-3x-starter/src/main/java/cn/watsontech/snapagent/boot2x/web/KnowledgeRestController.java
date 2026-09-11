package cn.watsontech.snapagent.boot2x.web;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import cn.watsontech.snapagent.boot2x.codegraph.CodeGraphTools;
import cn.watsontech.snapagent.boot2x.codegraph.ModuleArchitectureTools;
import cn.watsontech.snapagent.boot2x.knowledge.KnowledgeReloadService;
import cn.watsontech.snapagent.core.codegraph.CodeGraphIndex;
import cn.watsontech.snapagent.core.codegraph.CodeGraphNode;
import cn.watsontech.snapagent.core.tool.ToolCallback;
import cn.watsontech.snapagent.core.tool.ToolCallbacks;
import cn.watsontech.snapagent.core.tool.ToolResult;
import cn.watsontech.snapagent.core.vectorstore.Document;
import cn.watsontech.snapagent.core.vectorstore.SearchRequest;
import cn.watsontech.snapagent.core.vectorstore.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller for knowledge base and code graph queries.
 *
 * <p>Provides endpoints for the knowledge modal in the frontend UI:</p>
 * <ul>
 *   <li>{@code /knowledge/status} — knowledge base statistics</li>
 *   <li>{@code /knowledge/search} — search knowledge fragments</li>
 *   <li>{@code /knowledge/fragments} — list all fragments (by source)</li>
 *   <li>{@code /knowledge/reload} — reload knowledge base</li>
 *   <li>{@code /knowledge/upload} — upload a markdown file</li>
 *   <li>{@code /knowledge/codegraph/status} — code graph statistics</li>
 *   <li>{@code /knowledge/codegraph/search} — search code graph nodes</li>
 *   <li>{@code /knowledge/codegraph/render} — render call graph as HTML</li>
 * </ul>
 */
@RestController
@RequestMapping("/knowledge")
public class KnowledgeRestController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeRestController.class);

    private final ObjectProvider<VectorStore> vectorStoreProvider;
    private final ObjectProvider<CodeGraphIndex> codeGraphIndexProvider;
    private final ObjectProvider<CodeGraphTools> codeGraphToolsProvider;
    private final ObjectProvider<ModuleArchitectureTools> moduleArchToolsProvider;
    private final ObjectProvider<KnowledgeReloadService> knowledgeReloadServiceProvider;
    private final SnapAgentProperties props;

    public KnowledgeRestController(
            ObjectProvider<VectorStore> vectorStoreProvider,
            ObjectProvider<CodeGraphIndex> codeGraphIndexProvider,
            ObjectProvider<CodeGraphTools> codeGraphToolsProvider,
            ObjectProvider<ModuleArchitectureTools> moduleArchToolsProvider,
            ObjectProvider<KnowledgeReloadService> knowledgeReloadServiceProvider,
            SnapAgentProperties props) {
        this.vectorStoreProvider = vectorStoreProvider;
        this.codeGraphIndexProvider = codeGraphIndexProvider;
        this.codeGraphToolsProvider = codeGraphToolsProvider;
        this.moduleArchToolsProvider = moduleArchToolsProvider;
        this.knowledgeReloadServiceProvider = knowledgeReloadServiceProvider;
        this.props = props;
    }

    // ---- Knowledge Base endpoints ----

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        VectorStore vs = vectorStoreProvider.getIfAvailable();
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        if (vs == null) {
            data.put("enabled", false);
            data.put("fragmentCount", 0);
            return ResponseEntity.ok(data);
        }

        int count = 0;
        try {
            // Use reflection or a known method to get count
            if (vs instanceof cn.watsontech.snapagent.core.vectorstore.InMemoryVectorStore) {
                count = ((cn.watsontech.snapagent.core.vectorstore.InMemoryVectorStore) vs).size();
            }
        } catch (Exception e) {
            log.warn("Failed to get vector store size: {}", e.getMessage());
        }

        data.put("enabled", true);
        data.put("fragmentCount", count);
        data.put("maxFragments", props.getKnowledge().getMaxFragments());
        data.put("minScore", props.getKnowledge().getMinScore());

        // Sources
        List<Map<String, Object>> sources = new ArrayList<Map<String, Object>>();
        if (props.getKnowledge().getSources() != null) {
            for (SnapAgentProperties.KnowledgeSourceConfig src : props.getKnowledge().getSources()) {
                Map<String, Object> s = new LinkedHashMap<String, Object>();
                s.put("type", src.getType() != null ? src.getType() : "directory");
                s.put("dir", src.getDir());
                s.put("writable", false);
                sources.add(s);
            }
        }
        data.put("sources", sources);
        return ResponseEntity.ok(data);
    }

    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> search(@RequestParam("q") String query) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        VectorStore vs = vectorStoreProvider.getIfAvailable();
        if (vs == null) {
            result.put("fragments", new ArrayList<>());
            return ResponseEntity.ok(result);
        }

        try {
            SearchRequest req = new SearchRequest(query, 10, 0.0, null);
            List<Document> docs = vs.similaritySearch(req);
            List<Map<String, Object>> fragments = new ArrayList<Map<String, Object>>();
            for (Document doc : docs) {
                Map<String, Object> f = new LinkedHashMap<String, Object>();
                f.put("id", doc.getId());
                f.put("title", doc.getMetadata("title") != null ? doc.getMetadata("title").toString() : "(untitled)");
                f.put("content", doc.getContent());
                f.put("source", doc.getMetadata("source") != null ? doc.getMetadata("source").toString() : "unknown");
                f.put("version", doc.getVersion() != null ? doc.getVersion() : "1");
                fragments.add(f);
            }
            result.put("fragments", fragments);
        } catch (Exception e) {
            log.warn("Knowledge search failed: {}", e.getMessage());
            result.put("fragments", new ArrayList<>());
            result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/fragments")
    public ResponseEntity<Map<String, Object>> fragments() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        VectorStore vs = vectorStoreProvider.getIfAvailable();
        if (vs == null) {
            result.put("fragments", new ArrayList<>());
            return ResponseEntity.ok(result);
        }

        try {
            List<Map<String, Object>> fragments = new ArrayList<Map<String, Object>>();
            if (vs instanceof cn.watsontech.snapagent.core.vectorstore.InMemoryVectorStore) {
                List<Document> allDocs = ((cn.watsontech.snapagent.core.vectorstore.InMemoryVectorStore) vs).listAll();
                for (Document doc : allDocs) {
                    Map<String, Object> f = new LinkedHashMap<String, Object>();
                    f.put("id", doc.getId());
                    f.put("title", doc.getMetadata("title") != null ? doc.getMetadata("title").toString() : "(untitled)");
                    f.put("content", doc.getContent());
                    f.put("source", doc.getMetadata("source") != null ? doc.getMetadata("source").toString() : "unknown");
                    f.put("version", doc.getVersion() != null ? doc.getVersion() : "1");
                    fragments.add(f);
                }
            }
            result.put("fragments", fragments);
        } catch (Exception e) {
            result.put("fragments", new ArrayList<>());
            result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/reload")
    public ResponseEntity<Map<String, Object>> reload() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        KnowledgeReloadService reloadService = knowledgeReloadServiceProvider.getIfAvailable();
        if (reloadService == null) {
            result.put("status", "unavailable");
            result.put("message", "Knowledge reload service not available (vectorstore disabled?)");
            return ResponseEntity.ok(result);
        }

        try {
            KnowledgeReloadService.ReloadResult r = reloadService.reload();
            if (r == null) {
                result.put("status", "ok");
                result.put("message", "No knowledge infrastructure to reload");
                result.put("fragmentCount", 0);
                return ResponseEntity.ok(result);
            }
            result.put("status", "ok");
            result.put("documentsLoaded", r.getDocumentsLoaded());
            result.put("conceptsLoaded", r.getConceptsLoaded());
            result.put("vectorStoreCleared", r.isVectorStoreCleared());
            result.put("domainIndexCleared", r.isDomainIndexCleared());
        } catch (Exception e) {
            log.warn("Knowledge reload failed: {}", e.getMessage());
            result.put("status", "error");
            result.put("message", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (file == null || file.isEmpty()) {
            result.put("error", "No file uploaded");
            return ResponseEntity.badRequest().body(result);
        }

        try {
            String knowledgeDir = props.getUploadSkillsDir() + "/knowledge";
            File dir = new File(knowledgeDir);
            dir.mkdirs();

            String filename = file.getOriginalFilename();
            if (filename == null) filename = "upload.md";
            File target = new File(dir, filename);

            FileOutputStream fos = new FileOutputStream(target);
            try {
                fos.write(file.getBytes());
            } finally {
                fos.close();
            }

            // Add to vector store
            VectorStore vs = vectorStoreProvider.getIfAvailable();
            if (vs != null) {
                String content = new String(file.getBytes(), StandardCharsets.UTF_8);
                Map<String, Object> metadata = new LinkedHashMap<String, Object>();
                metadata.put("source", "upload");
                metadata.put("title", filename);
                Document doc = new Document(content, metadata);
                List<Document> docs = new ArrayList<Document>();
                docs.add(doc);
                vs.add(docs);
            }

            result.put("status", "ok");
            result.put("filename", filename);
        } catch (Exception e) {
            result.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
        return ResponseEntity.ok(result);
    }

    // ---- Code Graph endpoints ----

    @GetMapping("/codegraph/status")
    public ResponseEntity<Map<String, Object>> codeGraphStatus() {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        CodeGraphIndex index = codeGraphIndexProvider.getIfAvailable();
        if (index == null) {
            data.put("enabled", false);
            data.put("nodeCount", 0);
            data.put("edgeCount", 0);
            return ResponseEntity.ok(data);
        }

        data.put("enabled", true);
        data.put("nodeCount", index.nodeCount());
        data.put("persistence", props.getCodeGraph().getPersistence());
        data.put("scanPackages", props.getCodeGraph().getScanPackages());
        data.put("scanMode", props.getCodeGraph().getScanMode());
        data.put("hotReloadEnabled", props.getCodeGraph().isHotReloadEnabled());
        return ResponseEntity.ok(data);
    }

    @GetMapping("/codegraph/search")
    public ResponseEntity<Map<String, Object>> codeGraphSearch(@RequestParam("q") String query) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        CodeGraphIndex index = codeGraphIndexProvider.getIfAvailable();
        if (index == null) {
            result.put("nodes", new ArrayList<>());
            return ResponseEntity.ok(result);
        }

        try {
            List<CodeGraphNode> nodes = index.findByName(query);
            List<Map<String, Object>> nodeList = new ArrayList<Map<String, Object>>();
            for (CodeGraphNode node : nodes) {
                Map<String, Object> n = new LinkedHashMap<String, Object>();
                n.put("id", node.getId());
                n.put("type", node.getType().name());
                n.put("name", node.getName());
                n.put("packageName", node.getPackageName());
                n.put("filePath", node.getFilePath());
                n.put("lineNumber", node.getLineNumber());
                n.put("returnType", node.getReturnType());
                // Count edges
                n.put("outgoingEdges", index.getOutgoingEdges(node.getId()).size());
                n.put("incomingEdges", index.getIncomingEdges(node.getId()).size());
                nodeList.add(n);
            }
            result.put("nodes", nodeList);
        } catch (Exception e) {
            result.put("nodes", new ArrayList<>());
            result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/codegraph/render")
    public ResponseEntity<Map<String, Object>> codeGraphRender(@RequestParam Map<String, String> body) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        CodeGraphTools tools = codeGraphToolsProvider.getIfAvailable();
        if (tools == null) {
            result.put("error", "CodeGraphTools not available");
            return ResponseEntity.ok(result);
        }

        try {
            String query = body.get("query");
            String graphType = body.get("graphType");
            String maxDepthStr = body.get("maxDepth");
            Integer maxDepth = maxDepthStr != null ? Integer.parseInt(maxDepthStr) : null;

            // Call render_call_graph tool
            ToolCallback[] callbacks = ToolCallbacks.from(tools);
            ToolCallback renderCb = null;
            for (ToolCallback cb : callbacks) {
                if ("render_call_graph".equals(cb.getName())) {
                    renderCb = cb;
                    break;
                }
            }
            if (renderCb == null) {
                result.put("error", "render_call_graph tool not found");
                return ResponseEntity.ok(result);
            }

            Map<String, Object> args = new HashMap<String, Object>();
            args.put("query", query);
            args.put("graphType", graphType);
            if (maxDepth != null) args.put("maxDepth", maxDepth);

            ToolResult toolResult = renderCb.execute(args, null);
            result.put("result", toolResult.getContent());
            result.put("success", toolResult.isSuccess());
        } catch (Exception e) {
            result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }
}
