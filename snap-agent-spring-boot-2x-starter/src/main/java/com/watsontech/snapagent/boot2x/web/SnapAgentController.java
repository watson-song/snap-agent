package com.watsontech.snapagent.boot2x.web;

import com.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import com.watsontech.snapagent.boot2x.routing.PeerSseRelay;
import com.watsontech.snapagent.core.agent.AgentExecutor;
import com.watsontech.snapagent.core.agent.AgentTask;
import com.watsontech.snapagent.core.agent.RateLimiter;
import com.watsontech.snapagent.core.agent.TaskStatus;
import com.watsontech.snapagent.core.agent.TaskStore;
import com.watsontech.snapagent.core.agent.TranscriptEvent;
import com.watsontech.snapagent.core.llm.LlmClient;
import com.watsontech.snapagent.core.llm.Message;
import com.watsontech.snapagent.core.security.SecurityAuditLogger;
import com.watsontech.snapagent.core.security.SecurityGateway;
import com.watsontech.snapagent.core.security.UserInfo;
import com.watsontech.snapagent.core.skill.InputSpec;
import com.watsontech.snapagent.core.skill.SkillAvailability;
import com.watsontech.snapagent.core.skill.SkillMeta;
import com.watsontech.snapagent.core.skill.SkillRegistry;
import com.watsontech.snapagent.core.tool.ToolDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * REST + SSE controller for the SnapAgent.
 *
 * <p>All endpoints are mounted under the configured {@code basePath}
 * (default {@code /snap-agent}, design doc 06 §1).
 * Authentication is delegated to the host via {@link SecurityGateway}.</p>
 */
@RestController
@RequestMapping("${snap-agent.base-path:/snap-agent}")
public class SnapAgentController {

    private static final Logger log = LoggerFactory.getLogger(SnapAgentController.class);

    private final SkillRegistry skillRegistry;
    private final AgentExecutor agentExecutor;
    private final TaskStore taskStore;
    private final ToolDispatcher toolDispatcher;
    private final SnapAgentProperties properties;
    private final SecurityGateway securityGateway;
    private final RateLimiter rateLimiter;
    private final AsyncTaskExecutor taskExecutor;
    private final PeerSseRelay peerSseRelay;
    private final LlmClient llmClient;
    private final SecurityAuditLogger auditLogger;

    public SnapAgentController(SkillRegistry skillRegistry,
                                AgentExecutor agentExecutor,
                                TaskStore taskStore,
                                ToolDispatcher toolDispatcher,
                                SnapAgentProperties properties,
                                SecurityGateway securityGateway,
                                RateLimiter rateLimiter,
                                AsyncTaskExecutor taskExecutor) {
        this(skillRegistry, agentExecutor, taskStore, toolDispatcher,
                properties, securityGateway, rateLimiter, taskExecutor, null, null, null);
    }

    public SnapAgentController(SkillRegistry skillRegistry,
                                AgentExecutor agentExecutor,
                                TaskStore taskStore,
                                ToolDispatcher toolDispatcher,
                                SnapAgentProperties properties,
                                SecurityGateway securityGateway,
                                RateLimiter rateLimiter,
                                AsyncTaskExecutor taskExecutor,
                                PeerSseRelay peerSseRelay) {
        this(skillRegistry, agentExecutor, taskStore, toolDispatcher,
                properties, securityGateway, rateLimiter, taskExecutor, peerSseRelay, null, null);
    }

    public SnapAgentController(SkillRegistry skillRegistry,
                                AgentExecutor agentExecutor,
                                TaskStore taskStore,
                                ToolDispatcher toolDispatcher,
                                SnapAgentProperties properties,
                                SecurityGateway securityGateway,
                                RateLimiter rateLimiter,
                                AsyncTaskExecutor taskExecutor,
                                PeerSseRelay peerSseRelay,
                                LlmClient llmClient) {
        this(skillRegistry, agentExecutor, taskStore, toolDispatcher,
                properties, securityGateway, rateLimiter, taskExecutor, peerSseRelay, llmClient, null);
    }

    public SnapAgentController(SkillRegistry skillRegistry,
                                AgentExecutor agentExecutor,
                                TaskStore taskStore,
                                ToolDispatcher toolDispatcher,
                                SnapAgentProperties properties,
                                SecurityGateway securityGateway,
                                RateLimiter rateLimiter,
                                AsyncTaskExecutor taskExecutor,
                                PeerSseRelay peerSseRelay,
                                LlmClient llmClient,
                                SecurityAuditLogger auditLogger) {
        this.skillRegistry = skillRegistry;
        this.agentExecutor = agentExecutor;
        this.taskStore = taskStore;
        this.toolDispatcher = toolDispatcher;
        this.properties = properties;
        this.securityGateway = securityGateway;
        this.rateLimiter = rateLimiter;
        this.taskExecutor = taskExecutor;
        this.peerSseRelay = peerSseRelay;
        this.llmClient = llmClient;
        this.auditLogger = auditLogger;
    }

    // ---- GET /user-info (public, no auth required) ----
    @GetMapping("/user-info")
    public ResponseEntity<Object> getUserInfo() {
        UserInfo info = new UserInfo();
        if (securityGateway == null) {
            info.setMessage("security not configured");
            return ResponseEntity.ok(info);
        }
        String userId = securityGateway.currentUserId();
        if (userId == null) {
            return ResponseEntity.ok(info);
        }
        info.setUserId(userId);
        info.setUsername(userId);
        info.setAuthenticated(true);
        boolean authorized = securityGateway.hasPermission(
                properties.getSecurity().getRequiredPermission());
        info.setAuthorized(authorized);
        if (!authorized) {
            info.setMessage("您未授权，请联系管理员授权访问");
        }
        return ResponseEntity.ok(info);
    }

    // ---- GET /skills ----
    @GetMapping("/skills")
    public ResponseEntity<Object> listSkills() {
        ResponseEntity<Object> authError = requireAuth();
        if (authError != null) return authError;

        audit(currentUserId(), "GET", "/skills", "LIST_SKILLS", null);

        List<SkillMeta> skills = skillRegistry.all();
        List<Map<String, Object>> skillList = new ArrayList<Map<String, Object>>();
        for (SkillMeta skill : skills) {
            skillList.add(toSkillDto(skill));
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("skills", skillList);
        return ResponseEntity.ok(result);
    }

    // ---- GET /tools ----
    @GetMapping("/tools")
    public ResponseEntity<Object> listTools() {
        ResponseEntity<Object> authError = requireAuth();
        if (authError != null) return authError;

        audit(currentUserId(), "GET", "/tools", "LIST_TOOLS", null);

        Set<String> names = toolDispatcher.availableToolNames();
        List<Map<String, Object>> toolList = new ArrayList<Map<String, Object>>();
        for (String name : names) {
            Map<String, Object> tool = new LinkedHashMap<String, Object>();
            tool.put("name", name);
            toolList.add(tool);
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("tools", toolList);
        return ResponseEntity.ok(result);
    }

    // ---- GET /models ----
    @GetMapping("/models")
    public ResponseEntity<Object> listModels() {
        ResponseEntity<Object> authError = requireAuth();
        if (authError != null) return authError;

        audit(currentUserId(), "GET", "/models", "LIST_MODELS", null);

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        List<String> models = new ArrayList<String>();
        if (llmClient != null) {
            List<String> apiModels = llmClient.listModels();
            if (!apiModels.isEmpty()) {
                models = apiModels;
            }
        }
        if (models.isEmpty()) {
            models = properties.getLlm().getAllowedModels();
        }
        result.put("default", properties.getLlm().getModel());
        result.put("allowed", models);
        return ResponseEntity.ok(result);
    }

    // ---- POST /skills/refresh ----
    @PostMapping("/skills/refresh")
    public ResponseEntity<Object> refreshSkills() {
        ResponseEntity<Object> authError = requireAuth();
        if (authError != null) return authError;

        audit(currentUserId(), "POST", "/skills/refresh", "REFRESH_SKILLS", null);

        SkillRegistry.RefreshResult rr = skillRegistry.refresh();
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("total", rr.getTotal());
        result.put("available", rr.getAvailable());
        result.put("unavailable", rr.getUnavailable());
        result.put("invalid", rr.getInvalid());
        return ResponseEntity.ok(result);
    }

    // ---- DELETE /skills/{name} ----
    @DeleteMapping("/skills/{name}")
    public ResponseEntity<Object> deleteSkill(@PathVariable String name) {
        ResponseEntity<Object> authError = requireAuth();
        if (authError != null) return authError;

        if (name == null || name.isEmpty()) {
            return errorResponse(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "skill name is required");
        }

        // Check if skill exists at all
        SkillMeta skill = skillRegistry.get(name);
        if (skill == null) {
            return errorResponse(HttpStatus.NOT_FOUND, "SKILL_NOT_FOUND",
                    "skill not found: " + name);
        }

        // Builtin-only skills cannot be deleted
        Path customPath = skillRegistry.getCustomSkillPath(name);
        if (customPath == null) {
            return errorResponse(HttpStatus.FORBIDDEN, "BUILTIN_SKILL",
                    "cannot delete builtin skill: " + name);
        }

        audit(currentUserId(), "DELETE", "/skills/" + name, "DELETE_SKILL",
                Collections.<String, Object>singletonMap("skill", name));

        try {
            if (Files.isDirectory(customPath)) {
                // Directory skill — delete entire directory recursively
                deleteRecursively(customPath);
                log.info("Deleted directory skill '{}' at {}", name, customPath);
            } else {
                Files.deleteIfExists(customPath);
                log.info("Deleted file skill '{}' at {}", name, customPath);
            }

            SkillRegistry.RefreshResult rr = skillRegistry.refresh();
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("deleted", name);
            result.put("total", rr.getTotal());
            result.put("available", rr.getAvailable());
            result.put("unavailable", rr.getUnavailable());
            result.put("invalid", rr.getInvalid());
            // Check if a builtin was restored
            result.put("builtinRestored", skillRegistry.isBuiltin(name));
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to delete skill '{}': {}", name, e.getMessage());
            return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "DELETE_ERROR",
                    "failed to delete skill: " + e.getMessage());
        }
    }

    private void deleteRecursively(Path path) throws java.io.IOException {
        if (Files.isDirectory(path)) {
            try (java.util.stream.Stream<Path> entries = Files.list(path)) {
                for (Path entry : (Iterable<Path>) entries::iterator) {
                    deleteRecursively(entry);
                }
            }
        }
        Files.deleteIfExists(path);
    }

    // ---- POST /skills/upload ----
    @PostMapping("/skills/upload")
    public ResponseEntity<Object> uploadSkill(@RequestParam("file") MultipartFile file) {
        ResponseEntity<Object> authError = requireAuth();
        if (authError != null) return authError;

        if (file == null || file.isEmpty()) {
            return errorResponse(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "file is empty");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || filename.isEmpty()) {
            return errorResponse(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "filename is empty");
        }

        String skillsDirPath = properties.getUploadSkillsDir();
        if (skillsDirPath == null || skillsDirPath.isEmpty()) {
            return errorResponse(HttpStatus.BAD_REQUEST, "CONFIG_ERROR",
                    "upload-skills-dir is not configured");
        }
        if (skillsDirPath.startsWith("file:")) {
            skillsDirPath = skillsDirPath.substring(5);
        }

        try {
            Path skillsDir = Paths.get(skillsDirPath);
            if (!Files.isDirectory(skillsDir)) {
                Files.createDirectories(skillsDir);
            }

            if (filename.toLowerCase().endsWith(".zip")) {
                // Extract zip to a subdirectory named after the zip filename
                String dirName = filename.substring(0, filename.length() - 4);
                Path destDir = skillsDir.resolve(dirName);
                Files.createDirectories(destDir);
                Path tempFile = Files.createTempFile("skill-upload-", ".zip");
                file.transferTo(tempFile.toFile());
                try (ZipFile zipFile = new ZipFile(tempFile.toFile())) {
                    Enumeration<? extends ZipEntry> entries = zipFile.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry entry = entries.nextElement();
                        Path destPath = destDir.resolve(entry.getName());
                        if (entry.isDirectory()) {
                            Files.createDirectories(destPath);
                        } else {
                            Files.createDirectories(destPath.getParent());
                            try (OutputStream os = new BufferedOutputStream(
                                    new FileOutputStream(destPath.toFile()))) {
                                java.io.InputStream is = zipFile.getInputStream(entry);
                                byte[] buffer = new byte[8192];
                                int len;
                                while ((len = is.read(buffer)) > 0) {
                                    os.write(buffer, 0, len);
                                }
                            }
                        }
                    }
                }
                Files.deleteIfExists(tempFile);
            } else if (filename.toLowerCase().endsWith(".md")) {
                // Save single .md file directly
                Path destFile = skillsDir.resolve(filename);
                Files.write(destFile, file.getBytes());
            } else {
                return errorResponse(HttpStatus.BAD_REQUEST, "INVALID_INPUT",
                        "only .md and .zip files are supported");
            }

            // Refresh the skill registry
            SkillRegistry.RefreshResult rr = skillRegistry.refresh();
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("filename", filename);
            result.put("total", rr.getTotal());
            result.put("available", rr.getAvailable());
            result.put("unavailable", rr.getUnavailable());
            result.put("invalid", rr.getInvalid());
            audit(currentUserId(), "POST", "/skills/upload", "UPLOAD_SKILL",
                    Collections.<String, Object>singletonMap("filename", filename));
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to upload skill file {}: {}", filename, e.getMessage());
            return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "UPLOAD_ERROR",
                    "failed to save file: " + e.getMessage());
        }
    }

    // ---- POST /skills/upload-folder ----
    @PostMapping("/skills/upload-folder")
    public ResponseEntity<Object> uploadSkillFolder(
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(value = "dirName", required = false) String dirName) {
        ResponseEntity<Object> authError = requireAuth();
        if (authError != null) return authError;

        if (files == null || files.length == 0) {
            return errorResponse(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "no files provided");
        }

        String skillsDirPath = properties.getUploadSkillsDir();
        if (skillsDirPath == null || skillsDirPath.isEmpty()) {
            return errorResponse(HttpStatus.BAD_REQUEST, "CONFIG_ERROR",
                    "upload-skills-dir is not configured");
        }
        if (skillsDirPath.startsWith("file:")) {
            skillsDirPath = skillsDirPath.substring(5);
        }

        try {
            Path skillsDir = Paths.get(skillsDirPath);
            if (!Files.isDirectory(skillsDir)) {
                Files.createDirectories(skillsDir);
            }

            // Determine target directory
            Path destDir;
            if (dirName != null && !dirName.isEmpty()) {
                destDir = skillsDir.resolve(dirName);
            } else {
                // Use the common parent directory name from the first file
                String firstPath = files[0].getOriginalFilename();
                if (firstPath != null && firstPath.contains("/")) {
                    dirName = firstPath.split("/")[0];
                    destDir = skillsDir.resolve(dirName);
                } else {
                    destDir = skillsDir;
                }
            }
            Files.createDirectories(destDir);

            int saved = 0;
            for (MultipartFile file : files) {
                String relativePath = file.getOriginalFilename();
                if (relativePath == null || relativePath.isEmpty()) continue;

                // If dirName was provided, strip it from the path
                if (dirName != null && relativePath.startsWith(dirName + "/")) {
                    relativePath = relativePath.substring(dirName.length() + 1);
                }

                Path destPath = destDir.resolve(relativePath);
                Files.createDirectories(destPath.getParent());
                Files.write(destPath, file.getBytes());
                saved++;
            }

            SkillRegistry.RefreshResult rr = skillRegistry.refresh();
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("dirName", dirName != null ? dirName : "");
            result.put("filesSaved", saved);
            result.put("total", rr.getTotal());
            result.put("available", rr.getAvailable());
            result.put("unavailable", rr.getUnavailable());
            result.put("invalid", rr.getInvalid());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to upload skill folder: {}", e.getMessage());
            return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "UPLOAD_ERROR",
                    "failed to save files: " + e.getMessage());
        }
    }

    // ---- POST /runs ----
    @PostMapping("/runs")
    public ResponseEntity<Object> createRun(@RequestBody Map<String, Object> body) {
        String userId = securityGateway.currentUserId();
        if (userId == null) {
            return errorResponse(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "not authenticated");
        }

        // Check permission
        if (!securityGateway.hasPermission(properties.getSecurity().getRequiredPermission())) {
            return errorResponse(HttpStatus.FORBIDDEN, "FORBIDDEN", "permission denied");
        }

        String skillId = (String) body.get("skillId");
        if (skillId == null || skillId.isEmpty()) {
            return errorResponse(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "skillId is required");
        }

        SkillMeta skill = skillRegistry.get(skillId);
        if (skill == null) {
            return errorResponse(HttpStatus.NOT_FOUND, "SKILL_NOT_FOUND", "skill not found: " + skillId);
        }

        if (skill.getAvailability() != SkillAvailability.AVAILABLE) {
            return errorResponse(HttpStatus.CONFLICT, "SKILL_UNAVAILABLE",
                    "skill unavailable: " + skill.getUnavailableReason());
        }

        // Extract inputs (safe conversion from Map<String, Object> to Map<String, String>)
        Map<String, String> inputs = extractInputs(body.get("inputs"));
        String validationError = validateInputs(skill, inputs);
        if (validationError != null) {
            return errorResponse(HttpStatus.BAD_REQUEST, "INVALID_INPUT", validationError);
        }

        // Auto-inject log paths for log_read skills
        if (skill.getTools().contains("log_read") && inputs != null) {
            List<String> logPaths = properties.getLogs().getAllowedPaths();
            if (logPaths != null && !logPaths.isEmpty()) {
                inputs.put("_log_paths", String.join(", ", logPaths));
            }
        }

        // Validate model — check against dynamic API list first, then static config
        String model = (String) body.get("model");
        if (model != null && !model.isEmpty()) {
            List<String> allowed = properties.getLlm().getAllowedModels();
            if (llmClient != null) {
                List<String> apiModels = llmClient.listModels();
                if (!apiModels.isEmpty()) {
                    allowed = apiModels;
                }
            }
            if (!allowed.contains(model)) {
                return errorResponse(HttpStatus.BAD_REQUEST, "MODEL_NOT_ALLOWED",
                        "model not in allowed list: " + model);
            }
        } else {
            model = properties.getLlm().getModel();
        }

        // Rate limit
        if (!rateLimiter.tryAcquire(userId)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", "30")
                    .body(errorBody("RATE_LIMITED", "rate limit exceeded"));
        }

        // Create task (with optional conversation history for follow-up questions)
        List<Message> history = extractHistory(body.get("history"));
        AgentTask task = AgentTask.create(userId, skillId, inputs, model, history);
        taskStore.save(task);

        // Submit to executor
        final AgentTask finalTask = task;
        final SkillMeta finalSkill = skill;
        final String finalUserId = userId;
        try {
            taskExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        agentExecutor.execute(finalTask, finalSkill);
                    } catch (RuntimeException e) {
                        log.error("Agent execution failed for task {}: {}",
                                finalTask.getTaskId(), e.getMessage());
                        finalTask.setStatus(TaskStatus.FAILED);
                        taskStore.update(finalTask);
                    } finally {
                        rateLimiter.release(finalUserId);
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            rateLimiter.releaseRejected(userId);
            task.setStatus(TaskStatus.FAILED);
            taskStore.update(task);
            log.error("Task executor rejected task {}: {}", task.getTaskId(), e.getMessage());
            return errorResponse(HttpStatus.SERVICE_UNAVAILABLE, "EXECUTOR_REJECTED",
                    "server busy, please retry later");
        }

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("taskId", task.getTaskId());
        result.put("status", task.getStatus().name());
        result.put("streamUrl", properties.getBasePath() + "/runs/" + task.getTaskId() + "/stream");

        Map<String, Object> auditDetails = new LinkedHashMap<String, Object>();
        auditDetails.put("skillId", skillId);
        auditDetails.put("model", model);
        auditDetails.put("taskId", task.getTaskId());
        audit(userId, "POST", "/runs", "RUN_SKILL", auditDetails);

        return ResponseEntity.accepted().body(result);
    }

    // ---- GET /runs/{id} ----
    @GetMapping("/runs/{id}")
    public ResponseEntity<Object> getRun(@PathVariable String id) {
        String userId = securityGateway.currentUserId();
        if (userId == null) {
            return errorResponse(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "not authenticated");
        }
        AgentTask task = taskStore.get(id);
        if (task == null) {
            return errorResponse(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "task not found: " + id);
        }
        if (!userId.equals(task.getUserId())) {
            return errorResponse(HttpStatus.FORBIDDEN, "FORBIDDEN", "not task owner");
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("taskId", task.getTaskId());
        result.put("status", task.getStatus().name());
        result.put("skillId", task.getSkillId());
        result.put("model", task.getModel());
        result.put("createdAt", task.getCreatedAt());
        result.put("updatedAt", task.getUpdatedAt());
        return ResponseEntity.ok(result);
    }

    // ---- GET /runs/{id}/transcript ----
    @GetMapping("/runs/{id}/transcript")
    public ResponseEntity<Object> getTranscript(@PathVariable String id) {
        String userId = securityGateway.currentUserId();
        if (userId == null) {
            return errorResponse(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "not authenticated");
        }
        AgentTask task = taskStore.get(id);
        if (task == null) {
            return errorResponse(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "task not found: " + id);
        }
        if (!userId.equals(task.getUserId())) {
            return errorResponse(HttpStatus.FORBIDDEN, "FORBIDDEN", "not task owner");
        }
        List<Map<String, Object>> events = new ArrayList<Map<String, Object>>();
        for (TranscriptEvent event : task.getTranscript()) {
            Map<String, Object> eventMap = new LinkedHashMap<String, Object>();
            eventMap.put("type", event.getType());
            if (event.getText() != null) {
                eventMap.put("text", event.getText());
            }
            if (event.getData() != null && !event.getData().isEmpty()) {
                eventMap.put("data", event.getData());
            }
            eventMap.put("timestamp", event.getTimestamp());
            events.add(eventMap);
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("transcript", events);
        return ResponseEntity.ok(result);
    }

    // ---- GET /runs/{id}/stream (SSE) ----
    @GetMapping("/runs/{id}/stream")
    public SseEmitter streamRun(@PathVariable String id,
                                @RequestParam(value = "token", required = false) String token) {
        SseEmitter emitter = new SseEmitter(30L * 60L * 1000L);

        // Auth check — if token query param is present, use it (EventSource can't
        // send custom headers). Otherwise fall back to SecurityGateway.
        String userId = null;
        boolean tokenAuth = false;
        if (token != null) {
            try {
                byte[] decoded = java.util.Base64.getDecoder().decode(token);
                String credentials = new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
                userId = credentials.split(":", 2)[0];
                tokenAuth = true;
            } catch (Exception e) {
                // Invalid token
            }
        }
        if (userId == null) {
            userId = securityGateway.currentUserId();
        }
        if (userId == null) {
            try {
                emitter.send(SseEmitter.event().name("error")
                        .data(errorBody("UNAUTHORIZED", "not authenticated")));
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }

        final AgentTask task = taskStore.get(id);
        if (task == null) {
            // Task not local — probe peers (cross-pod relay) if configured.
            if (peerSseRelay != null) {
                taskExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        boolean relayed = false;
                        try {
                            relayed = peerSseRelay.tryRelay(emitter, id);
                        } catch (Exception e) {
                            log.warn("Peer relay failed for task {}: {}", id, e.getMessage());
                        }
                        if (!relayed) {
                            try {
                                emitter.send(SseEmitter.event().name("error")
                                        .data(errorBody("TASK_NOT_FOUND",
                                                "task not found locally or on any peer: " + id)));
                                emitter.complete();
                            } catch (Exception e) {
                                emitter.completeWithError(e);
                            }
                        }
                    }
                });
                return emitter;
            }
            try {
                emitter.send(SseEmitter.event().name("error")
                        .data(errorBody("TASK_NOT_FOUND", "task not found: " + id)));
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }

        // Ownership check (IDOR prevention) — skip when using token auth
        // or when SecurityGateway provided a different userId (session mismatch)
        // Task ID is a random unguessable ID; knowing it is sufficient for streaming.
        if (!tokenAuth && userId != null && task.getUserId() != null
                && !userId.equals(task.getUserId())) {
            try {
                emitter.send(SseEmitter.event().name("error")
                        .data(errorBody("FORBIDDEN", "not task owner")));
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }

        // True streaming: replay existing transcript events, then poll the
        // stream queue for real-time token-by-token delivery.
        log.info("SSE stream requested for task {}, status={}", id, task.getStatus());
        taskExecutor.execute(new Runnable() {
            @Override
            public void run() {
                log.info("SSE streaming started for task {}", id);
                try {
                    // 1. Replay existing transcript events (catch-up)
                    // Skip "done" and "error" type events — "done" is sent as a
                    // terminal SSE event separately, and "error" conflicts with
                    // EventSource's built-in error event (would trigger reconnect).
                    // Limit replay to last 200 events to avoid overwhelming the
                    // browser with a massive burst (full transcript available via
                    // GET /runs/{id}/transcript).
                    List<TranscriptEvent> existing = task.getTranscript();
                    int replayStart = Math.max(0, existing.size() - 200);
                    int replayCount = existing.size() - replayStart;
                    log.info("SSE replay for task {}: {} total events, replaying last {}",
                            id, existing.size(), replayCount);
                    for (int i = replayStart; i < existing.size(); i++) {
                        TranscriptEvent event = existing.get(i);
                        if (TranscriptEvent.TYPE_DONE.equals(event.getType())
                                || TranscriptEvent.TYPE_ERROR.equals(event.getType())) {
                            continue;
                        }
                        emitter.send(SseEmitter.event()
                                .name(event.getType())
                                .data(toSseData(event)));
                    }

                    // 2. If already terminal, send done and return
                    if (isTerminal(task.getStatus())) {
                        log.info("SSE task {} already terminal, sending done", id);
                        emitter.send(SseEmitter.event().name("done")
                                .data(task.getStatus().name()));
                        emitter.complete();
                        return;
                    }

                    // 3. Poll stream queue for real-time events
                    long lastHeartbeat = System.currentTimeMillis();
                    while (true) {
                        TranscriptEvent event = task.pollStreamEvent(500);
                        if (event != null) {
                            // Skip "done" and "error" transcript events in SSE —
                            // "done" is sent as terminal SSE event below, and
                            // "error" triggers EventSource's error handler.
                            if (TranscriptEvent.TYPE_DONE.equals(event.getType())
                                    || TranscriptEvent.TYPE_ERROR.equals(event.getType())) {
                                // But do send error info as a custom "task_error" event
                                if (TranscriptEvent.TYPE_ERROR.equals(event.getType())) {
                                    emitter.send(SseEmitter.event()
                                            .name("task_error")
                                            .data(toSseData(event)));
                                }
                            } else {
                                emitter.send(SseEmitter.event()
                                        .name(event.getType())
                                        .data(toSseData(event)));
                            }
                        }
                        // Send heartbeat every 15s to keep connection alive
                        long now = System.currentTimeMillis();
                        if (now - lastHeartbeat > 15000) {
                            emitter.send(SseEmitter.event().comment("heartbeat"));
                            lastHeartbeat = now;
                        }
                        // Check terminal after sending events
                        if (isTerminal(task.getStatus())) {
                            log.info("SSE task {} terminal, draining and sending done", id);
                            // Drain any remaining events
                            List<TranscriptEvent> remaining = task.drainStreamEvents();
                            for (TranscriptEvent re : remaining) {
                                if (TranscriptEvent.TYPE_DONE.equals(re.getType())
                                        || TranscriptEvent.TYPE_ERROR.equals(re.getType())) {
                                    if (TranscriptEvent.TYPE_ERROR.equals(re.getType())) {
                                        emitter.send(SseEmitter.event()
                                                .name("task_error")
                                                .data(toSseData(re)));
                                    }
                                    continue;
                                }
                                emitter.send(SseEmitter.event()
                                        .name(re.getType())
                                        .data(toSseData(re)));
                            }
                            emitter.send(SseEmitter.event().name("done")
                                    .data(task.getStatus().name()));
                            emitter.complete();
                            log.info("SSE streaming completed for task {}", id);
                            return;
                        }
                    }
                } catch (Exception e) {
                    log.warn("Stream error for task {}: {}", id, e.getMessage());
                    emitter.completeWithError(e);
                }
            }
        });
        log.info("SSE stream setup complete for task {}", id);

        return emitter;
    }

    // ---- helpers ----

    /**
     * Checks authentication and permission. Returns null if OK,
     * or an error ResponseEntity if not authenticated/authorized.
     */
    private ResponseEntity<Object> requireAuth() {
        if (securityGateway == null) {
            return errorResponse(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "security not configured");
        }
        String userId = securityGateway.currentUserId();
        if (userId == null) {
            return errorResponse(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "not authenticated");
        }
        if (!securityGateway.hasPermission(properties.getSecurity().getRequiredPermission())) {
            return errorResponse(HttpStatus.FORBIDDEN, "FORBIDDEN", "permission denied");
        }
        return null;
    }

    /** Returns the current authenticated user id, or null. */
    private String currentUserId() {
        return securityGateway != null ? securityGateway.currentUserId() : null;
    }

    /** Records an audit event if an audit logger is configured. */
    private void audit(String userId, String method, String path, String action,
                       Map<String, Object> details) {
        if (auditLogger != null) {
            auditLogger.onApiAccess(userId, method, path, action, details);
        }
    }

    private Map<String, Object> toSkillDto(SkillMeta skill) {
        Map<String, Object> dto = new LinkedHashMap<String, Object>();
        dto.put("name", skill.getName());
        dto.put("description", skill.getDescription());
        dto.put("availability", skill.getAvailability().name());
        dto.put("tools", skill.getTools());
        dto.put("source", skill.getSource());
        dto.put("overridesBuiltin", skill.isOverridesBuiltin());
        if (skill.getUnavailableReason() != null) {
            dto.put("unavailableReason", skill.getUnavailableReason());
        }
        List<Map<String, Object>> inputs = new ArrayList<Map<String, Object>>();
        for (InputSpec spec : skill.getInputs()) {
            Map<String, Object> input = new LinkedHashMap<String, Object>();
            input.put("key", spec.getKey());
            input.put("label", spec.getLabel());
            input.put("required", spec.isRequired());
            input.put("type", spec.getType());
            if (!spec.getOptions().isEmpty()) {
                input.put("options", spec.getOptions());
            }
            inputs.add(input);
        }
        dto.put("inputs", inputs);
        // Shortcuts
        List<Map<String, Object>> shortcuts = new ArrayList<Map<String, Object>>();
        for (com.watsontech.snapagent.core.skill.Shortcut sc : skill.getShortcuts()) {
            Map<String, Object> scDto = new LinkedHashMap<String, Object>();
            scDto.put("label", sc.getLabel());
            scDto.put("message", sc.getMessage());
            shortcuts.add(scDto);
        }
        dto.put("shortcuts", shortcuts);
        // Log paths for log_read skills
        if (skill.getTools().contains("log_read")) {
            dto.put("logPaths", properties.getLogs().getAllowedPaths());
        }
        return dto;
    }

    private String validateInputs(SkillMeta skill, Map<String, String> inputs) {
        for (InputSpec spec : skill.getInputs()) {
            String value = inputs != null ? inputs.get(spec.getKey()) : null;
            if (spec.isRequired() && (value == null || value.isEmpty())) {
                return "missing required input: " + spec.getKey();
            }
            if (value != null && !value.isEmpty() && spec.getType() != null) {
                String typeError = validateType(spec, value);
                if (typeError != null) {
                    return typeError;
                }
            }
        }
        return null;
    }

    private String validateType(InputSpec spec, String value) {
        String type = spec.getType();
        if ("enum".equals(type)) {
            if (!spec.getOptions().contains(value)) {
                return "invalid enum value for " + spec.getKey() + ": " + value;
            }
        } else if ("number".equals(type)) {
            try {
                Double.parseDouble(value);
            } catch (NumberFormatException e) {
                return "invalid number for " + spec.getKey();
            }
        } else if ("date".equals(type)) {
            try {
                LocalDate.parse(value);
            } catch (Exception e) {
                return "invalid date for " + spec.getKey();
            }
        } else if ("boolean".equals(type)) {
            if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                return "invalid boolean for " + spec.getKey();
            }
        }
        return null;
    }

    private boolean isTerminal(TaskStatus status) {
        return status == TaskStatus.SUCCEEDED
                || status == TaskStatus.FAILED
                || status == TaskStatus.TIMEOUT
                || status == TaskStatus.CANCELLED;
    }

    private Object toSseData(TranscriptEvent event) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        if (event.getText() != null) {
            data.put("text", event.getText());
        }
        if (event.getData() != null && !event.getData().isEmpty()) {
            data.putAll(event.getData());
        }
        return data;
    }

    private ResponseEntity<Object> errorResponse(HttpStatus status, String error, String message) {
        return ResponseEntity.status(status).body(errorBody(error, message));
    }

    private Map<String, Object> errorBody(String error, String message) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("error", error);
        body.put("message", message);
        return body;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> extractInputs(Object raw) {
        if (raw == null) {
            return new HashMap<String, String>();
        }
        if (!(raw instanceof Map)) {
            return new HashMap<String, String>();
        }
        Map<String, String> result = new HashMap<String, String>();
        Map<String, ?> rawMap = (Map<String, ?>) raw;
        for (Map.Entry<String, ?> entry : rawMap.entrySet()) {
            Object value = entry.getValue();
            result.put(entry.getKey(), value != null ? value.toString() : "");
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Message> extractHistory(Object raw) {
        if (raw == null || !(raw instanceof List)) {
            return null;
        }
        List<?> rawList = (List<?>) raw;
        List<Message> messages = new ArrayList<Message>();
        for (Object item : rawList) {
            if (!(item instanceof Map)) continue;
            Map<String, Object> m = (Map<String, Object>) item;
            String role = m.get("role") instanceof String ? (String) m.get("role") : "user";
            String content = m.get("content") != null ? String.valueOf(m.get("content")) : "";
            if ("assistant".equals(role)) {
                messages.add(Message.assistant(content, null));
            } else {
                messages.add(Message.user(content));
            }
        }
        return messages.isEmpty() ? null : messages;
    }
}
