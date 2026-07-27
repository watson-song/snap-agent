package cn.watsontech.snapagent.boot2x.routing;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class PeerSseRelayTest {

    private HttpServer server;
    private int port;
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldReturnFalseWhenInternalTokenEmpty() {
        PeerRouter router = new StaticPeerRouter(Collections.singletonList(
                "http://127.0.0.1:" + port));
        PeerSseRelay relay = new PeerSseRelay(router, "", "/snap-agent-internal", httpClient);
        SseEmitter emitter = new SseEmitter();
        assertThat(relay.tryRelay(emitter, "task-1")).isFalse();
    }

    @Test
    void shouldReturnFalseWhenNoPeersDiscovered() {
        PeerRouter router = new NoopPeerRouter();
        PeerSseRelay relay = new PeerSseRelay(router, "secret", "/snap-agent-internal", httpClient);
        SseEmitter emitter = new SseEmitter();
        assertThat(relay.tryRelay(emitter, "task-1")).isFalse();
    }

    @Test
    void shouldReturnFalseWhenAllPeersReturn404() {
        server.createContext("/snap-agent-internal/tasks/task-1/probe",
                new FixedStatusHandler(404, "{\"error\":\"TASK_NOT_FOUND\"}"));
        PeerRouter router = new StaticPeerRouter(Collections.singletonList(
                "http://127.0.0.1:" + port));
        PeerSseRelay relay = new PeerSseRelay(router, "secret", "/snap-agent-internal", httpClient);
        SseEmitter emitter = new SseEmitter();
        assertThat(relay.tryRelay(emitter, "task-1")).isFalse();
    }

    @Test
    void shouldReturnFalseWhenPeerReturns401() {
        server.createContext("/snap-agent-internal/tasks/task-1/probe",
                new FixedStatusHandler(401, "{\"error\":\"UNAUTHORIZED\"}"));
        PeerRouter router = new StaticPeerRouter(Collections.singletonList(
                "http://127.0.0.1:" + port));
        PeerSseRelay relay = new PeerSseRelay(router, "secret", "/snap-agent-internal", httpClient);
        SseEmitter emitter = new SseEmitter();
        assertThat(relay.tryRelay(emitter, "task-1")).isFalse();
    }

    @Test
    void shouldRelaySseEventsFromFirstAvailablePeer() throws Exception {
        // peer responds 200 to probe, then streams two events + done
        server.createContext("/snap-agent-internal/tasks/task-1/probe",
                new FixedStatusHandler(200, "{\"ok\":true}"));
        server.createContext("/snap-agent-internal/tasks/task-1/stream",
                new SseStreamHandler(
                        "event: thinking\ndata: {\"text\":\"hello\"}\n\nevent: done\ndata: SUCCEEDED\n\n"));
        PeerRouter router = new StaticPeerRouter(Collections.singletonList(
                "http://127.0.0.1:" + port));
        PeerSseRelay relay = new PeerSseRelay(router, "secret", "/snap-agent-internal", httpClient);

        // Use pipeSse directly with a mock emitter to verify specific events are relayed
        SseEmitter emitter = mock(SseEmitter.class);
        java.io.InputStream in = new java.io.ByteArrayInputStream(
                "event: thinking\ndata: {\"text\":\"hello\"}\n\nevent: done\ndata: SUCCEEDED\n\n"
                        .getBytes());
        relay.pipeSse(emitter, in);

        // Verify two SSE events were sent (thinking + done) and emitter was completed
        ArgumentCaptor<SseEmitter.SseEventBuilder> captor =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, times(2)).send(captor.capture());
        verify(emitter).complete();

        // Verify the event names: first event should be "thinking", second "done"
        List<SseEmitter.SseEventBuilder> events = captor.getAllValues();
        assertThat(sseFrameToString(events.get(0))).contains("event:thinking");
        assertThat(sseFrameToString(events.get(1))).contains("event:done");
    }

    @Test
    void shouldTryNextPeerWhenFirstUnreachable() throws Exception {
        // First peer uses a non-resolvable hostname — DNS fails immediately.
        String deadPeer = "http://nonexistent.invalid:8080";
        String livePeer = "http://127.0.0.1:" + port;

        server.createContext("/snap-agent-internal/tasks/task-1/probe",
                new FixedStatusHandler(200, "{\"ok\":true}"));
        server.createContext("/snap-agent-internal/tasks/task-1/stream",
                new SseStreamHandler("event: done\ndata: SUCCEEDED\n\n"));

        PeerRouter router = new StaticPeerRouter(java.util.Arrays.asList(deadPeer, livePeer));
        PeerSseRelay relay = new PeerSseRelay(router, "secret", "/snap-agent-internal", httpClient);
        SseEmitter emitter = new SseEmitter();
        boolean relayed = relay.tryRelay(emitter, "task-1");
        assertThat(relayed).isTrue();
    }

    @Test
    void shouldPipeMultiLineDataEvents() throws Exception {
        PeerRouter router = new NoopPeerRouter();
        PeerSseRelay relay = new PeerSseRelay(router, "secret", "/snap-agent-internal", httpClient);
        SseEmitter emitter = mock(SseEmitter.class);
        String multiLine = "event: tool_call\ndata: line1\ndata: line2\n\n";
        java.io.InputStream in = new java.io.ByteArrayInputStream(multiLine.getBytes());
        relay.pipeSse(emitter, in);

        // Verify a single event was sent (multi-line data concatenated into one event)
        ArgumentCaptor<SseEmitter.SseEventBuilder> captor =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, times(1)).send(captor.capture());
        verify(emitter).complete();

        // Verify the event data is the concatenation of multi-line data: "line1\nline2"
        String frame = sseFrameToString(captor.getValue());
        assertThat(frame).contains("event:tool_call");
        assertThat(frame).contains("line1\nline2");
    }

    @Test
    void shouldIgnoreCommentLines() throws Exception {
        PeerRouter router = new NoopPeerRouter();
        PeerSseRelay relay = new PeerSseRelay(router, "secret", "/snap-agent-internal", httpClient);
        SseEmitter emitter = mock(SseEmitter.class);
        String withComment = ": heartbeat\nevent: thinking\ndata: hi\n\n";
        java.io.InputStream in = new java.io.ByteArrayInputStream(withComment.getBytes());
        relay.pipeSse(emitter, in);

        // Verify only the thinking event was sent (comment line ignored)
        ArgumentCaptor<SseEmitter.SseEventBuilder> captor =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, times(1)).send(captor.capture());
        verify(emitter).complete();

        // Verify the sent event is "thinking" (not a comment)
        assertThat(sseFrameToString(captor.getValue())).contains("event:thinking");
    }

    @Test
    void shouldCompleteEmitterWhenStreamEnds() throws Exception {
        PeerRouter router = new NoopPeerRouter();
        PeerSseRelay relay = new PeerSseRelay(router, "secret", "/snap-agent-internal", httpClient);
        SseEmitter emitter = mock(SseEmitter.class);
        java.io.InputStream in = new java.io.ByteArrayInputStream(
                "event: done\ndata: SUCCEEDED\n\n".getBytes());
        relay.pipeSse(emitter, in);

        // Verify the emitter was completed when the stream ended
        verify(emitter).complete();
    }

    /** Serializes an SseEventBuilder to its SSE frame string for assertions. */
    private static String sseFrameToString(SseEmitter.SseEventBuilder builder) {
        StringBuilder sb = new StringBuilder();
        for (ResponseBodyEmitter.DataWithMediaType dmt : builder.build()) {
            sb.append(dmt.getData());
        }
        return sb.toString();
    }

    // ---- helpers ----

    static class FixedStatusHandler implements HttpHandler {
        private final int status;
        private final String body;

        FixedStatusHandler(int status, String body) {
            this.status = status;
            this.body = body;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            byte[] bytes = body.getBytes();
            // drain request body if present
            try (java.io.InputStream is = exchange.getRequestBody()) {
                while (is.read(new byte[1024]) != -1) { }
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    static class SseStreamHandler implements HttpHandler {
        private final String sseBody;

        SseStreamHandler(String sseBody) {
            this.sseBody = sseBody;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // drain request body
            try (java.io.InputStream is = exchange.getRequestBody()) {
                while (is.read(new byte[1024]) != -1) { }
            }
            byte[] bytes = sseBody.getBytes();
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
                os.flush();
            }
        }
    }
}
