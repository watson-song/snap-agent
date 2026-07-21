package cn.watsontech.snapagent.boot2x.issue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import cn.watsontech.snapagent.core.issue.IssueClosure;
import cn.watsontech.snapagent.core.issue.IssueStatus;
import cn.watsontech.snapagent.core.issue.SolutionOption;
import cn.watsontech.snapagent.core.issue.SolutionSuggestion;
import cn.watsontech.snapagent.core.issue.VerificationResult;
import cn.watsontech.snapagent.core.issue.IssueStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
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
 * Default {@link IssueStore} that persists issue closures as JSON files
 * under a storage directory (typically {@code {upload-skills-dir}/issues/}).
 *
 * <p>File layout: {@code {storageDir}/{issueId}.json}</p>
 *
 * <p>JSON is used for structured storage (easy to parse back). Each file
 * contains a single {@link IssueClosure} serialized as a JSON object.</p>
 */
public class FileIssueStore implements IssueStore {

    private static final Logger log = LoggerFactory.getLogger(FileIssueStore.class);

    private final Path storageDir;
    private final ObjectMapper mapper;

    public FileIssueStore(String storageDirPath) {
        this(storageDirPath, new ObjectMapper());
    }

    public FileIssueStore(String storageDirPath, ObjectMapper mapper) {
        String path = storageDirPath;
        if (path != null && path.startsWith("file:")) {
            path = path.substring(5);
        }
        this.storageDir = path != null ? Paths.get(path) : null;
        this.mapper = mapper;
        if (this.storageDir != null) {
            try {
                if (!Files.isDirectory(this.storageDir)) {
                    Files.createDirectories(this.storageDir);
                    log.info("Created issues directory: {}", this.storageDir);
                }
            } catch (IOException e) {
                log.warn("Failed to create issues directory {}: {}",
                        this.storageDir, e.getMessage());
            }
        }
    }

    @Override
    public void save(IssueClosure issue) {
        if (storageDir == null) {
            log.warn("Issues storage directory not configured; cannot save");
            return;
        }
        if (issue == null || issue.getIssueId() == null || issue.getIssueId().isEmpty()) {
            log.warn("Cannot save issue with null/empty issueId");
            return;
        }

        Path file = storageDir.resolve(issue.getIssueId() + ".json");
        try {
            Map<String, Object> data = toMap(issue);
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(data);
            Files.createDirectories(storageDir);
            Files.write(file, json.getBytes(StandardCharsets.UTF_8));
            log.debug("Saved issue {} to {}", issue.getIssueId(), file);
        } catch (IOException e) {
            log.error("Failed to save issue {}: {}", issue.getIssueId(), e.getMessage());
        }
    }

    @Override
    public IssueClosure load(String issueId) {
        if (storageDir == null || issueId == null || issueId.isEmpty()) {
            return null;
        }
        Path file = storageDir.resolve(issueId + ".json");
        if (!Files.exists(file)) {
            return null;
        }
        try {
            String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            Map<String, Object> data = mapper.readValue(json,
                    new TypeReference<Map<String, Object>>() {});
            return fromMap(data);
        } catch (Exception e) {
            log.warn("Failed to load issue {}: {}", issueId, e.getMessage());
            return null;
        }
    }

    @Override
    public IssueClosure findByTaskId(String taskId) {
        if (storageDir == null || taskId == null || taskId.isEmpty()) {
            return null;
        }
        if (!Files.isDirectory(storageDir)) {
            return null;
        }
        try {
            for (Path file : Files.list(storageDir).toArray(Path[]::new)) {
                if (!file.toString().endsWith(".json")) {
                    continue;
                }
                try {
                    String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                    Map<String, Object> data = mapper.readValue(json,
                            new TypeReference<Map<String, Object>>() {});
                    String storedTaskId = str(data.get("taskId"));
                    if (taskId.equals(storedTaskId)) {
                        return fromMap(data);
                    }
                } catch (Exception e) {
                    log.warn("Failed to read issue file {}: {}", file, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Failed to scan issues directory for taskId {}: {}", taskId, e.getMessage());
        }
        return null;
    }

    @Override
    public List<IssueClosure> list() {
        if (storageDir == null || !Files.isDirectory(storageDir)) {
            return Collections.emptyList();
        }
        List<IssueClosure> issues = new ArrayList<IssueClosure>();
        try {
            for (Path file : Files.list(storageDir).toArray(Path[]::new)) {
                if (!file.toString().endsWith(".json")) {
                    continue;
                }
                try {
                    String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                    Map<String, Object> data = mapper.readValue(json,
                            new TypeReference<Map<String, Object>>() {});
                    issues.add(fromMap(data));
                } catch (Exception e) {
                    log.warn("Failed to read issue file {}: {}", file, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Failed to list issues directory: {}", e.getMessage());
            return Collections.emptyList();
        }
        // Sort by updatedAt descending (newest first)
        issues.sort((a, b) -> Long.compare(b.getUpdatedAt(), a.getUpdatedAt()));
        return issues;
    }

    @Override
    public List<IssueClosure> listByStatus(IssueStatus status) {
        List<IssueClosure> all = list();
        if (status == null) {
            return all;
        }
        List<IssueClosure> filtered = new ArrayList<IssueClosure>();
        for (IssueClosure issue : all) {
            if (status.equals(issue.getStatus())) {
                filtered.add(issue);
            }
        }
        return filtered;
    }

    @Override
    public void delete(String issueId) {
        if (storageDir == null || issueId == null || issueId.isEmpty()) {
            return;
        }
        Path file = storageDir.resolve(issueId + ".json");
        try {
            boolean deleted = Files.deleteIfExists(file);
            if (deleted) {
                log.info("Deleted issue {}", issueId);
            }
        } catch (IOException e) {
            log.error("Failed to delete issue {}: {}", issueId, e.getMessage());
        }
    }

    // ---- helpers ----

    private Map<String, Object> toMap(IssueClosure issue) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("issueId", issue.getIssueId());
        map.put("externalIssueId", issue.getExternalIssueId());
        map.put("taskId", issue.getTaskId());
        map.put("conversationId", issue.getConversationId());
        map.put("userId", issue.getUserId());
        map.put("userQuery", issue.getUserQuery());
        map.put("rootCause", issue.getRootCause());
        map.put("solution", solutionToMap(issue.getSolution()));
        map.put("selectedSolution", issue.getSelectedSolution());
        map.put("status", issue.getStatus() != null ? issue.getStatus().name() : null);
        map.put("fixCommitId", issue.getFixCommitId());
        map.put("verificationResult", verificationToMap(issue.getVerificationResult()));
        map.put("knowledgeEntryId", issue.getKnowledgeEntryId());
        map.put("createdAt", issue.getCreatedAt());
        map.put("updatedAt", issue.getUpdatedAt());
        return map;
    }

    /** Serializes a {@link SolutionSuggestion} to a nested map, or {@code null}. */
    private Map<String, Object> solutionToMap(SolutionSuggestion suggestion) {
        if (suggestion == null) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> optionsList = new ArrayList<Map<String, Object>>();
        if (suggestion.getOptions() != null) {
            for (SolutionOption option : suggestion.getOptions()) {
                Map<String, Object> optMap = new LinkedHashMap<String, Object>();
                optMap.put("id", option.getId());
                optMap.put("title", option.getTitle());
                optMap.put("description", option.getDescription());
                optMap.put("effort", option.getEffort());
                optMap.put("temporary", option.isTemporary());
                optionsList.add(optMap);
            }
        }
        map.put("options", optionsList);
        map.put("recommendedOptionId", suggestion.getRecommendedOptionId());
        map.put("rationale", suggestion.getRationale());
        map.put("relatedCode", suggestion.getRelatedCode());
        return map;
    }

    /** Serializes a {@link VerificationResult} to a nested map, or {@code null}. */
    private Map<String, Object> verificationToMap(VerificationResult result) {
        if (result == null) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("passed", result.isPassed());
        map.put("summary", result.getSummary());
        map.put("beforeStatus", result.getBeforeStatus());
        map.put("afterStatus", result.getAfterStatus());
        map.put("verifiedAt", result.getVerifiedAt());
        return map;
    }

    @SuppressWarnings("unchecked")
    private IssueClosure fromMap(Map<String, Object> data) {
        SolutionSuggestion solution = solutionFromMap(data.get("solution"));
        if (solution == null) {
            // Backward compat: legacy files stored "solutions" as a List<String>.
            solution = solutionFromLegacyList(data.get("solutions"));
        }

        VerificationResult verificationResult = verificationFromMap(data.get("verificationResult"));
        if (verificationResult == null) {
            // Backward compat: legacy files stored "verificationResult" as a plain String.
            verificationResult = verificationFromLegacyString(data.get("verificationResult"));
        }

        IssueStatus status = null;
        Object statusObj = data.get("status");
        if (statusObj != null) {
            try {
                status = IssueStatus.valueOf(statusObj.toString());
            } catch (IllegalArgumentException e) {
                log.warn("Unknown issue status: {}", statusObj);
            }
        }

        return new IssueClosure(
                str(data.get("issueId")),
                nullableStr(data.get("externalIssueId")),
                str(data.get("taskId")),
                nullableStr(data.get("conversationId")),
                nullableStr(data.get("userId")),
                str(data.get("userQuery")),
                str(data.get("rootCause")),
                solution,
                nullableStr(data.get("selectedSolution")),
                status,
                nullableStr(data.get("fixCommitId")),
                verificationResult,
                nullableStr(data.get("knowledgeEntryId")),
                longVal(data.get("createdAt")),
                longVal(data.get("updatedAt"))
        );
    }

    /** Reconstructs a {@link SolutionSuggestion} from a nested map. */
    @SuppressWarnings("unchecked")
    private SolutionSuggestion solutionFromMap(Object raw) {
        if (!(raw instanceof Map)) {
            return null;
        }
        Map<String, Object> map = (Map<String, Object>) raw;
        List<SolutionOption> options = new ArrayList<SolutionOption>();
        Object optionsObj = map.get("options");
        if (optionsObj instanceof List) {
            for (Object item : (List<Object>) optionsObj) {
                if (item instanceof Map) {
                    Map<String, Object> optMap = (Map<String, Object>) item;
                    options.add(new SolutionOption(
                            nullableStr(optMap.get("id")),
                            nullableStr(optMap.get("title")),
                            nullableStr(optMap.get("description")),
                            nullableStr(optMap.get("effort")),
                            boolVal(optMap.get("temporary"))));
                }
            }
        }
        return new SolutionSuggestion(options,
                nullableStr(map.get("recommendedOptionId")),
                nullableStr(map.get("rationale")),
                nullableStr(map.get("relatedCode")));
    }

    /** Builds a {@link SolutionSuggestion} from a legacy {@code List<String>}. */
    @SuppressWarnings("unchecked")
    private SolutionSuggestion solutionFromLegacyList(Object raw) {
        if (!(raw instanceof List)) {
            return null;
        }
        List<SolutionOption> options = new ArrayList<SolutionOption>();
        int index = 1;
        for (Object item : (List<Object>) raw) {
            if (item != null) {
                String text = item.toString();
                options.add(new SolutionOption("opt-" + index, text, text, "medium", false));
                index++;
            }
        }
        String recommended = options.isEmpty() ? null : "opt-1";
        return new SolutionSuggestion(options, recommended, null, null);
    }

    /** Reconstructs a {@link VerificationResult} from a nested map. */
    @SuppressWarnings("unchecked")
    private VerificationResult verificationFromMap(Object raw) {
        if (!(raw instanceof Map)) {
            return null;
        }
        Map<String, Object> map = (Map<String, Object>) raw;
        return new VerificationResult(
                boolVal(map.get("passed")),
                nullableStr(map.get("summary")),
                nullableStr(map.get("beforeStatus")),
                nullableStr(map.get("afterStatus")),
                longVal(map.get("verifiedAt")));
    }

    /**
     * Builds a {@link VerificationResult} from a legacy plain-string
     * verification result. Passed is inferred from "通过"/"pass" keywords.
     */
    private VerificationResult verificationFromLegacyString(Object raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.toString();
        boolean passed = text.contains("通过") || text.toLowerCase().contains("pass");
        return new VerificationResult(passed, text, null, null, 0L);
    }

    private static boolean boolVal(Object obj) {
        if (obj == null) return false;
        if (obj instanceof Boolean) return (Boolean) obj;
        return Boolean.parseBoolean(obj.toString());
    }

    private static String str(Object obj) {
        return obj != null ? obj.toString() : "";
    }

    private static String nullableStr(Object obj) {
        return obj != null ? obj.toString() : null;
    }

    private static long longVal(Object obj) {
        if (obj == null) return 0;
        if (obj instanceof Number) return ((Number) obj).longValue();
        try {
            return Long.parseLong(obj.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
