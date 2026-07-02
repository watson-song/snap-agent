package com.watsontech.snapagent.boot2x.web;

import com.watsontech.snapagent.boot2x.routing.PeerSseRelay;
import com.watsontech.snapagent.core.agent.AgentTask;
import com.watsontech.snapagent.core.agent.TaskStatus;
import com.watsontech.snapagent.core.agent.TaskStore;
import com.watsontech.snapagent.core.agent.TranscriptEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Internal pod-to-pod endpoints for cross-pod SSE relay.
 *
 * <p>Mounted at a path OUTSIDE the user-facing {@code basePath} (default
 * {@code /snap-agent-internal}) so that the host's user-facing security
 * filter chain does not gate pod-to-pod traffic. The only credential is the
 * shared secret {@link PeerSseRelay#INTERNAL_TOKEN_HEADER}, verified on every
 * request. Hosts must permit this path (e.g. Spring Security
 * {@code antMatchers("/snap-agent-internal/**").permitAll()}).</p>
 *
 * <p>Two endpoints:
 * <ul>
 *   <li>{@code GET /tasks/{id}/probe} — 200 if this pod owns the task, 404
 *       otherwise, 401 if token bad. Used by {@link PeerSseRelay} to locate
 *       the owner pod before opening a long-lived stream.</li>
 *   <li>{@code GET /tasks/{id}/stream} — SSE stream of the task's transcript,
 *       same framing as the user-facing endpoint. No user auth/ownership
 *       check (the originating pod already did that). Sends an {@code error}
 *       SSE event if the task is not found locally.</li>
 * </ul>
 */
@RestController
@RequestMapping("${snap-agent.routing.internal-path:/snap-agent-internal}")
public class InternalTaskController {

    private static final Logger log = LoggerFactory.getLogger(InternalTaskController.class);

    private final TaskStore taskStore;
    private final String internalToken;
    private final AsyncTaskExecutor taskExecutor;

    public InternalTaskController(TaskStore taskStore, String internalToken,
                                 AsyncTaskExecutor taskExecutor) {
        this.taskStore = taskStore;
        this.internalToken = internalToken;
        this.taskExecutor = taskExecutor;
    }

    // ---- GET /tasks/{id}/probe ----
    @GetMapping("/tasks/{id}/probe")
    public ResponseEntity<Object> probe(
            @PathVariable String id,
            @RequestHeader(value = PeerSseRelay.INTERNAL_TOKEN_HEADER, required = false) String token) {

        if (!tokenValid(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(errorBody("UNAUTHORIZED", "invalid internal token"));
        }
        AgentTask task = taskStore.get(id);
        if (task == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(errorBody("TASK_NOT_FOUND", "task not found on this pod: " + id));
        }
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("taskId", task.getTaskId());
        body.put("status", task.getStatus().name());
        body.put("ownerPod", true);
        return ResponseEntity.ok(body);
    }

    // ---- GET /tasks/{id}/stream (SSE) ----
    @GetMapping("/tasks/{id}/stream")
    public SseEmitter stream(
            @PathVariable String id,
            @RequestHeader(value = PeerSseRelay.INTERNAL_TOKEN_HEADER, required = false) String token) {

        SseEmitter emitter = new SseEmitter(30L * 60L * 1000L);

        if (!tokenValid(token)) {
            try {
                emitter.send(SseEmitter.event().name("error")
                        .data(errorBody("UNAUTHORIZED", "invalid internal token")));
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }

        final AgentTask task = taskStore.get(id);
        if (task == null) {
            try {
                emitter.send(SseEmitter.event().name("error")
                        .data(errorBody("TASK_NOT_FOUND", "task not found on this pod: " + id)));
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }

        taskExecutor.execute(new Runnable() {
            @Override
            public void run() {
                int sentIndex = 0;
                try {
                    while (true) {
                        List<TranscriptEvent> transcript = task.getTranscript();
                        while (sentIndex < transcript.size()) {
                            TranscriptEvent event = transcript.get(sentIndex);
                            emitter.send(SseEmitter.event()
                                    .name(event.getType())
                                    .data(toSseData(event)));
                            sentIndex++;
                        }
                        if (isTerminal(task.getStatus())) {
                            emitter.send(SseEmitter.event().name("done")
                                    .data(task.getStatus().name()));
                            emitter.complete();
                            return;
                        }
                        Thread.sleep(200);
                    }
                } catch (Exception e) {
                    emitter.completeWithError(e);
                }
            }
        });

        return emitter;
    }

    // ---- helpers ----

    private boolean tokenValid(String token) {
        if (internalToken == null || internalToken.isEmpty()) {
            // if no token configured, internal endpoint is disabled
            return false;
        }
        return internalToken.equals(token);
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

    private Map<String, Object> errorBody(String error, String message) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("error", error);
        body.put("message", message);
        return body;
    }
}
