package cn.watsontech.snapagent.boot2x.issue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import cn.watsontech.snapagent.core.issue.IssueClosure;
import cn.watsontech.snapagent.core.issue.IssueStatus;
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
        map.put("userQuery", issue.getUserQuery());
        map.put("rootCause", issue.getRootCause());
        map.put("solutions", issue.getSolutions());
        map.put("selectedSolution", issue.getSelectedSolution());
        map.put("status", issue.getStatus() != null ? issue.getStatus().name() : null);
        map.put("fixCommitId", issue.getFixCommitId());
        map.put("verificationResult", issue.getVerificationResult());
        map.put("knowledgeEntryId", issue.getKnowledgeEntryId());
        map.put("createdAt", issue.getCreatedAt());
        map.put("updatedAt", issue.getUpdatedAt());
        return map;
    }

    @SuppressWarnings("unchecked")
    private IssueClosure fromMap(Map<String, Object> data) {
        List<String> solutions = new ArrayList<String>();
        Object solsObj = data.get("solutions");
        if (solsObj instanceof List) {
            for (Object item : (List<Object>) solsObj) {
                solutions.add(item != null ? item.toString() : "");
            }
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
                str(data.get("userQuery")),
                str(data.get("rootCause")),
                solutions,
                nullableStr(data.get("selectedSolution")),
                status,
                nullableStr(data.get("fixCommitId")),
                nullableStr(data.get("verificationResult")),
                nullableStr(data.get("knowledgeEntryId")),
                longVal(data.get("createdAt")),
                longVal(data.get("updatedAt"))
        );
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
