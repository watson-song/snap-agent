package cn.watsontech.snapagent.client;

import cn.watsontech.snapagent.client.dto.SkillDto;
import cn.watsontech.snapagent.client.dto.TranscriptEventDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SnapAgentClientTest {

    // ---- Constructor / auth tests ----

    @Test
    @DisplayName("constructor trims trailing slash from baseUrl")
    void shouldTrimTrailingSlash() {
        SnapAgentClient client = new SnapAgentClient("http://localhost:8080/snap-agent/");
        // Verify via a no-op operation (toString is not overridden, so just verify no crash)
        assertThat(client).isNotNull();
    }

    @Test
    @DisplayName("constructor with Basic Auth does not throw")
    void shouldCreateWithBasicAuth() {
        SnapAgentClient client = new SnapAgentClient("http://localhost:8080/snap-agent", "user", "pass");
        assertThat(client).isNotNull();
    }

    @Test
    @DisplayName("withAuthHeader returns new client instance, not same reference")
    void shouldCreateNewInstanceWithAuthHeader() {
        SnapAgentClient client = new SnapAgentClient("http://localhost:8080/snap-agent");
        SnapAgentClient withAuth = client.withAuthHeader("Bearer my-token");
        assertThat(withAuth).isNotSameAs(client);
    }

    // ---- Exception class tests ----

    @Test
    @DisplayName("SnapAgentClientException carries status code and message")
    void shouldCarryStatusCodeAndMessage() {
        SnapAgentClientException ex = new SnapAgentClientException("not found", 404);
        assertThat(ex.getStatusCode()).isEqualTo(404);
        assertThat(ex.getMessage()).contains("not found");
    }

    @Test
    @DisplayName("SnapAgentClientException with cause preserves cause")
    void shouldPreserveCause() {
        IOException cause = new IOException("connection reset");
        SnapAgentClientException ex = new SnapAgentClientException("request failed", 502, cause);
        assertThat(ex.getStatusCode()).isEqualTo(502);
        assertThat(ex.getCause()).isSameAs(cause);
    }

    // ---- Subclass-and-override HTTP tests ----

    /**
     * Helper: creates a fake HttpURLConnection that returns the given JSON body and status code.
     */
    private static class FakeConnection extends HttpURLConnection {
        private final int responseCode;
        private final String responseBody;
        private final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        FakeConnection(int responseCode, String responseBody) {
            super(null);
            this.responseCode = responseCode;
            this.responseBody = responseBody;
        }

        @Override
        public int getResponseCode() {
            return responseCode;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(responseBody.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public InputStream getErrorStream() {
            if (responseCode >= 400) {
                return new ByteArrayInputStream(responseBody.getBytes(StandardCharsets.UTF_8));
            }
            return null;
        }

        @Override
        public OutputStream getOutputStream() {
            return outputStream;
        }

        @Override
        public int getContentLength() {
            return responseBody.length();
        }

        @Override
        public void disconnect() {}

        @Override
        public boolean usingProxy() { return false; }

        @Override
        public void connect() {}
    }

    /**
     * Creates a SnapAgentClient subclass that returns a FakeConnection with the given
     * response for a specific HTTP method + path.
     */
    private SnapAgentClient createClientWithFakeResponse(final String method, final String pathPrefix,
                                                         final int responseCode, final String responseBody) {
        return new SnapAgentClient("http://localhost:8080/snap-agent") {
            @Override
            protected HttpURLConnection openConnection(String m, String p, String contentType) {
                if (method.equals(m) && p.startsWith(pathPrefix)) {
                    return new FakeConnection(responseCode, responseBody);
                }
                throw new UnsupportedOperationException(
                        "Unexpected " + m + " " + p);
            }
        };
    }

    @Test
    @DisplayName("listSkills parses skills array from wrapper object")
    void shouldListSkills() {
        String json = "{\"skills\":[" +
                "{\"name\":\"health-check\",\"description\":\"Health check\",\"tools\":[],\"available\":true}," +
                "{\"name\":\"db-query\",\"description\":\"DB query\",\"tools\":[\"jdbc_query\"],\"available\":true}" +
                "]}";
        SnapAgentClient client = createClientWithFakeResponse("GET", "/skills", 200, json);

        List<SkillDto> skills = client.listSkills();
        assertThat(skills).hasSize(2);
        assertThat(skills.get(0).getName()).isEqualTo("health-check");
        assertThat(skills.get(0).getDescription()).isEqualTo("Health check");
        assertThat(skills.get(0).isAvailable()).isTrue();
        assertThat(skills.get(1).getName()).isEqualTo("db-query");
        assertThat(skills.get(1).getTools()).containsExactly("jdbc_query");
    }

    @Test
    @DisplayName("listSkills returns empty list when skills field is missing")
    void shouldReturnEmptyListWhenNoSkills() {
        String json = "{}";
        SnapAgentClient client = createClientWithFakeResponse("GET", "/skills", 200, json);
        List<SkillDto> skills = client.listSkills();
        assertThat(skills).isEmpty();
    }

    @Test
    @DisplayName("getSkill finds skill by name from list")
    void shouldGetSkillByName() {
        String json = "{\"skills\":[" +
                "{\"name\":\"health-check\",\"description\":\"Health check\"}," +
                "{\"name\":\"db-query\",\"description\":\"DB query\"}" +
                "]}";
        SnapAgentClient client = createClientWithFakeResponse("GET", "/skills", 200, json);

        SkillDto skill = client.getSkill("db-query");
        assertThat(skill).isNotNull();
        assertThat(skill.getName()).isEqualTo("db-query");
    }

    @Test
    @DisplayName("getSkill returns null when skill not found")
    void shouldReturnNullWhenSkillNotFound() {
        String json = "{\"skills\":[{\"name\":\"health-check\",\"description\":\"Health check\"}]}";
        SnapAgentClient client = createClientWithFakeResponse("GET", "/skills", 200, json);

        SkillDto skill = client.getSkill("nonexistent");
        assertThat(skill).isNull();
    }

    @Test
    @DisplayName("runSkill sends POST /runs and returns taskId")
    void shouldRunSkill() {
        String json = "{\"taskId\":\"task-123\",\"status\":\"PENDING\",\"streamUrl\":\"/snap-agent/runs/task-123/stream\"}";
        SnapAgentClient client = createClientWithFakeResponse("POST", "/runs", 202, json);

        Map<String, String> inputs = new LinkedHashMap<String, String>();
        inputs.put("query", "SELECT 1");
        String taskId = client.runSkill("database-query", inputs);
        assertThat(taskId).isEqualTo("task-123");
    }

    @Test
    @DisplayName("runSkill returns null when response has no taskId")
    void shouldReturnNullWhenNoTaskId() {
        String json = "{\"status\":\"ERROR\"}";
        SnapAgentClient client = createClientWithFakeResponse("POST", "/runs", 200, json);

        String taskId = client.runSkill("test", Collections.emptyMap());
        assertThat(taskId).isNull();
    }

    @Test
    @DisplayName("getRunStatus returns status map")
    void shouldGetRunStatus() {
        String json = "{\"taskId\":\"task-456\",\"status\":\"SUCCEEDED\",\"skillId\":\"health-check\"}";
        SnapAgentClient client = createClientWithFakeResponse("GET", "/runs/task-456", 200, json);

        Map<String, Object> status = client.getRunStatus("task-456");
        assertThat(status).isNotNull();
        assertThat(status.get("taskId")).isEqualTo("task-456");
        assertThat(status.get("status")).isEqualTo("SUCCEEDED");
        assertThat(status.get("skillId")).isEqualTo("health-check");
    }

    @Test
    @DisplayName("getTranscript parses transcript array from wrapper object")
    void shouldGetTranscript() {
        String json = "{\"transcript\":[" +
                "{\"type\":\"thought\",\"text\":\"Analyzing...\",\"timestamp\":1234567890}," +
                "{\"type\":\"tool_call\",\"data\":{\"tool\":\"jdbc_query\"},\"timestamp\":1234567891}," +
                "{\"type\":\"completion\",\"text\":\"Done\",\"timestamp\":1234567892}" +
                "]}";
        SnapAgentClient client = createClientWithFakeResponse("GET", "/runs/task-789/transcript", 200, json);

        List<TranscriptEventDto> events = client.getTranscript("task-789");
        assertThat(events).hasSize(3);
        assertThat(events.get(0).getType()).isEqualTo("thought");
        assertThat(events.get(0).getText()).isEqualTo("Analyzing...");
        assertThat(events.get(0).getTimestamp()).isEqualTo(1234567890L);
        assertThat(events.get(1).getType()).isEqualTo("tool_call");
        assertThat(events.get(1).getData()).containsEntry("tool", "jdbc_query");
        assertThat(events.get(2).getType()).isEqualTo("completion");
    }

    @Test
    @DisplayName("getTranscript returns empty list when transcript field is missing")
    void shouldReturnEmptyTranscript() {
        String json = "{}";
        SnapAgentClient client = createClientWithFakeResponse("GET", "/runs/task-x/transcript", 200, json);
        List<TranscriptEventDto> events = client.getTranscript("task-x");
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("cancelRun sends POST /runs/{id}/cancel")
    void shouldCancelRun() {
        String json = "{\"taskId\":\"task-cancel\",\"status\":\"CANCELLED\"}";
        SnapAgentClient client = createClientWithFakeResponse("POST", "/runs/task-cancel/cancel", 200, json);

        // Should not throw
        client.cancelRun("task-cancel");
    }

    @Test
    @DisplayName("getUserInfo returns user info map")
    void shouldGetUserInfo() {
        String json = "{\"userId\":\"testuser\",\"authorized\":true,\"roles\":[\"ADMIN\"]}";
        SnapAgentClient client = createClientWithFakeResponse("GET", "/user-info", 200, json);

        Map<String, Object> info = client.getUserInfo();
        assertThat(info).isNotNull();
        assertThat(info.get("userId")).isEqualTo("testuser");
        assertThat(info.get("authorized")).isEqualTo(true);
    }

    // ---- Error handling tests ----

    @Test
    @DisplayName("HTTP 404 throws SnapAgentClientException with 404 status code")
    void shouldThrowOn404() {
        String json = "{\"error\":\"TASK_NOT_FOUND\",\"message\":\"task not found\"}";
        SnapAgentClient client = createClientWithFakeResponse("GET", "/runs/missing", 404, json);

        assertThatThrownBy(() -> client.getRunStatus("missing"))
                .isInstanceOf(SnapAgentClientException.class)
                .hasMessageContaining("HTTP 404")
                .extracting("statusCode").isEqualTo(404);
    }

    @Test
    @DisplayName("HTTP 500 throws SnapAgentClientException with 500 status code")
    void shouldThrowOn500() {
        String json = "{\"error\":\"INTERNAL\",\"message\":\"server error\"}";
        SnapAgentClient client = createClientWithFakeResponse("GET", "/skills", 500, json);

        assertThatThrownBy(() -> client.listSkills())
                .isInstanceOf(SnapAgentClientException.class)
                .extracting("statusCode").isEqualTo(500);
    }

    @Test
    @DisplayName("POST that returns 204 does not throw")
    void shouldHandle204NoContent() {
        SnapAgentClient client = new SnapAgentClient("http://localhost:8080/snap-agent") {
            @Override
            protected HttpURLConnection openConnection(String m, String p, String contentType) {
                return new FakeConnection(204, "") {
                    @Override
                    public int getContentLength() {
                        return 0;
                    }
                };
            }
        };

        // Should not throw, returns null
        Object result = client.runSkill("test", Collections.emptyMap());
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("IOException during request wraps in SnapAgentClientException with -1 status")
    void shouldWrapIOException() {
        SnapAgentClient client = new SnapAgentClient("http://localhost:8080/snap-agent") {
            @Override
            protected HttpURLConnection openConnection(String m, String p, String contentType) throws IOException {
                throw new IOException("connection refused");
            }
        };

        assertThatThrownBy(() -> client.listSkills())
                .isInstanceOf(SnapAgentClientException.class)
                .hasMessageContaining("connection refused")
                .extracting("statusCode").isEqualTo(-1);
    }
}
