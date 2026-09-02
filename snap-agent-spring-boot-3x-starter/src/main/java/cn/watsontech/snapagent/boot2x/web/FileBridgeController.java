package cn.watsontech.snapagent.boot2x.web;

import cn.watsontech.snapagent.boot2x.bridge.FileBridgeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/snap-agent/bridge/file")
public class FileBridgeController {

    private static final Logger log = LoggerFactory.getLogger(FileBridgeController.class);

    private final FileBridgeService fileBridgeService;

    public FileBridgeController(FileBridgeService fileBridgeService) {
        this.fileBridgeService = fileBridgeService;
    }

    @GetMapping("/stream")
    public SseEmitter stream() {
        return fileBridgeService.registerEmitter();
    }

    @PostMapping("/result")
    public ResponseEntity<?> handleResult(@RequestBody Map<String, String> payload) {
        String id = payload.get("id");
        String content = payload.get("content");
        String error = payload.get("error");

        if (id == null || id.isEmpty()) {
            Map<String, String> errMap = new HashMap<>();
            errMap.put("error", "Missing id field");
            return ResponseEntity.badRequest().body(errMap);
        }

        fileBridgeService.handleResult(id, content, error);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("active", fileBridgeService.isActive());
        status.put("pending", fileBridgeService.getPendingCount());
        status.put("emitters", fileBridgeService.getEmitterCount());
        return ResponseEntity.ok(status);
    }
}
