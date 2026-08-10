package cn.watsontech.snapagent.boot2x.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class FileBridgeService {

    private static final Logger log = LoggerFactory.getLogger(FileBridgeService.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final long requestTimeoutMs;

    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, CompletableFuture<FileReadResult>> pending =
            new ConcurrentHashMap<>();

    public static class FileReadResult {
        private String filePath;
        private String content;
        private String error;

        public FileReadResult() {}

        public FileReadResult(String filePath, String content, String error) {
            this.filePath = filePath;
            this.content = content;
            this.error = error;
        }

        public String getFilePath() { return filePath; }
        public void setFilePath(String filePath) { this.filePath = filePath; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public String getError() { return error; }
        public void setError(String error) { this.error = error; }

        public boolean isSuccess() { return error == null || error.isEmpty(); }
    }

    public FileBridgeService(long requestTimeoutMs) {
        this.requestTimeoutMs = requestTimeoutMs;
    }

    public SseEmitter registerEmitter() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> {
            emitters.remove(emitter);
            log.debug("File bridge SSE emitter completed; {} remaining", emitters.size());
        });
        emitter.onTimeout(() -> {
            emitters.remove(emitter);
            log.debug("File bridge SSE emitter timed out; {} remaining", emitters.size());
        });
        log.info("File bridge SSE stream requested, {} emitter(s) active", emitters.size());
        return emitter;
    }

    public FileReadResult readFile(String filePath, long timeoutMs) {
        String requestId = "file-" + System.currentTimeMillis() + "-" +
                Integer.toHexString(filePath.hashCode());

        CompletableFuture<FileReadResult> future = new CompletableFuture<>();
        pending.put(requestId, future);

        long actualTimeout = timeoutMs > 0 ? timeoutMs : requestTimeoutMs;

        try {
            Map<String, Object> eventData = new HashMap<>();
            eventData.put("id", requestId);
            eventData.put("filePath", filePath);
            eventData.put("type", "file-read-request");

            boolean sent = false;
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event()
                            .name("file-read")
                            .data(eventData));
                    sent = true;
                } catch (Exception e) {
                    log.warn("Failed to send file-read request to emitter: {}", e.getMessage());
                    emitters.remove(emitter);
                }
            }

            if (!sent) {
                pending.remove(requestId);
                FileReadResult result = new FileReadResult();
                result.setFilePath(filePath);
                result.setError("No frontend connected to file bridge. " +
                        "Please open the SnapAgent UI in Chrome with the bridge extension installed.");
                return result;
            }

            FileReadResult result = future.get(actualTimeout, TimeUnit.MILLISECONDS);
            return result;

        } catch (TimeoutException e) {
            pending.remove(requestId);
            FileReadResult result = new FileReadResult();
            result.setFilePath(filePath);
            result.setError("File read timed out after " + actualTimeout + "ms. " +
                    "Check that the Chrome extension is installed and a directory is authorized.");
            return result;
        } catch (ExecutionException e) {
            pending.remove(requestId);
            FileReadResult result = new FileReadResult();
            result.setFilePath(filePath);
            result.setError("File read failed: " + e.getCause().getMessage());
            return result;
        } catch (Exception e) {
            pending.remove(requestId);
            FileReadResult result = new FileReadResult();
            result.setFilePath(filePath);
            result.setError("File read error: " + e.getMessage());
            return result;
        }
    }

    public void handleResult(String requestId, String content, String error) {
        CompletableFuture<FileReadResult> future = pending.remove(requestId);
        if (future != null) {
            FileReadResult result = new FileReadResult();
            result.setContent(content);
            result.setError(error);
            future.complete(result);
            log.info("File read result received: request={}, success={}",
                    requestId, error == null || error.isEmpty());
        } else {
            log.warn("Received file read result for unknown request: {}", requestId);
        }
    }

    public void handleError(String requestId, String error) {
        handleResult(requestId, null, error);
    }

    public int getPendingCount() {
        return pending.size();
    }

    public int getEmitterCount() {
        return emitters.size();
    }

    public boolean isActive() {
        return !emitters.isEmpty();
    }
}
