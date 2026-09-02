package cn.watsontech.snapagent.boot2x.web;

import cn.watsontech.snapagent.boot2x.issue.BridgeClientStatus;
import cn.watsontech.snapagent.boot2x.issue.BridgeResponse;
import cn.watsontech.snapagent.boot2x.issue.BridgeStatus;
import cn.watsontech.snapagent.boot2x.issue.IssueBridgeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * REST + SSE controller for the browser network bridge.
 *
 * <p>Four endpoints mounted under the SnapAgent {@code basePath}:
 * <ul>
 *   <li>{@code GET  /bridge/stream} — SSE channel; server pushes
 *       {@code proxy-request} events, frontend keeps it open.</li>
 *   <li>{@code POST /bridge/result} — frontend posts the HTTP response
 *       back after fulfilling a proxy request.</li>
 *   <li>{@code GET  /bridge/status} — returns the current bridge status
 *       (connected, emitter count, pending count, extension state).</li>
 *   <li>{@code POST /bridge/status-update} — frontend reports the extension's
 *       installed/master/per-service state so the server knows whether to
 *       route through the bridge.</li>
 * </ul>
 *
 * <p>This controller is only assembled when {@code snap-agent.bridge.enabled=true}
 * (see {@code BridgeAutoConfiguration}).</p>
 */
@RestController
@RequestMapping("${snap-agent.base-path:/snap-agent}/bridge")
public class BridgeController {

    private static final Logger log = LoggerFactory.getLogger(BridgeController.class);

    private final IssueBridgeService bridgeService;

    public BridgeController(IssueBridgeService bridgeService) {
        this.bridgeService = bridgeService;
    }

    /**
     * SSE stream for the bridge frontend client.
     *
     * <p>The frontend opens this connection and keeps it alive. The server
     * pushes {@code proxy-request} events when {@link IssueBridgeService#proxyRequest}
     * is called by {@code BridgeHttpExecutor}.</p>
     */
    @GetMapping("/stream")
    public SseEmitter stream() {
        log.info("Bridge SSE stream requested");
        return bridgeService.registerEmitter();
    }

    /**
     * Receives the result of a proxied HTTP request from the frontend.
     */
    @PostMapping("/result")
    public ResponseEntity<Void> result(@RequestBody BridgeResponse response) {
        bridgeService.handleResult(response);
        return ResponseEntity.ok().build();
    }

    /**
     * Returns the current bridge status.
     */
    @GetMapping("/status")
    public ResponseEntity<BridgeStatus> status() {
        return ResponseEntity.ok(bridgeService.getStatus());
    }

    /**
     * Receives the extension's status from the frontend.
     *
     * <p>The frontend calls this on page load and whenever the extension's
     * configuration changes (master toggle, per-service toggles).</p>
     */
    @PostMapping("/status-update")
    public ResponseEntity<Void> statusUpdate(@RequestBody Map<String, Object> body) {
        boolean installed = Boolean.TRUE.equals(body.get("installed"));
        boolean masterEnabled = Boolean.TRUE.equals(body.get("masterEnabled"));
        @SuppressWarnings("unchecked")
        Map<String, Boolean> services = (Map<String, Boolean>) body.get("services");
        BridgeClientStatus status = new BridgeClientStatus(installed, masterEnabled, services);
        bridgeService.updateClientStatus(status);
        return ResponseEntity.ok().build();
    }
}
