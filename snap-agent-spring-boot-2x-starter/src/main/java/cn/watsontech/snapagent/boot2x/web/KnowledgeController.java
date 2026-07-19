package cn.watsontech.snapagent.boot2x.web;

import cn.watsontech.snapagent.core.knowledge.KnowledgeBase;
import cn.watsontech.snapagent.core.knowledge.KnowledgeFragment;
import cn.watsontech.snapagent.core.knowledge.SearchResult;
import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST endpoints for the embedded knowledge base (v0.7).
 *
 * <p>Only assembled when {@code snap-agent.knowledge.enabled=true}.
 * Exposes status and search endpoints so the web UI can display
 * knowledge fragments and let users test search queries.</p>
 */
@RestController
@ConditionalOnProperty(prefix = "snap-agent.knowledge", name = "enabled", havingValue = "true")
public class KnowledgeController {

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
}
