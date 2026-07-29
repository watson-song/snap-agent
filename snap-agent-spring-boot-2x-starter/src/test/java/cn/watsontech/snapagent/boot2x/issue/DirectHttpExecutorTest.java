package cn.watsontech.snapagent.boot2x.issue;

import cn.watsontech.snapagent.boot2x.test.MockHttpServer;
import cn.watsontech.snapagent.core.issue.HttpResponse;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link DirectHttpExecutor}.
 *
 * <p>Tests basic HTTP GET/POST against a mock server, and error handling
 * for unreachable hosts.</p>
 */
class DirectHttpExecutorTest {

    @Test
    void execute_shouldReturnResponseForGet() throws Exception {
        try (MockHttpServer server = new MockHttpServer()) {
            server.when("/api/data", "GET", 200, "{\"result\":\"ok\"}")
                  .start();

            DirectHttpExecutor executor = new DirectHttpExecutor();
            HttpResponse resp = executor.execute(
                    server.getBaseUrl() + "/api/data", "GET", null, null);

            assertThat(resp.getStatusCode()).isEqualTo(200);
            assertThat(resp.getBody()).contains("\"ok\"");
        }
    }

    @Test
    void execute_shouldReturnResponseForPost() throws Exception {
        try (MockHttpServer server = new MockHttpServer()) {
            server.when("/api/issues", "POST", 201, "{\"id\":42}")
                  .start();

            DirectHttpExecutor executor = new DirectHttpExecutor();
            HttpResponse resp = executor.execute(
                    server.getBaseUrl() + "/api/issues",
                    "POST",
                    Collections.singletonMap("Content-Type", "application/json"),
                    "{\"title\":\"test\"}");

            assertThat(resp.getStatusCode()).isEqualTo(201);
            assertThat(resp.getBody()).contains("42");

            MockHttpServer.RecordedRequest req = server.findRequest("POST", "/api/issues");
            assertThat(req).isNotNull();
            assertThat(req.body).contains("test");
        }
    }

    @Test
    void execute_shouldThrowForUnreachableHost() {
        DirectHttpExecutor executor = new DirectHttpExecutor();
        assertThatThrownBy(() -> executor.execute(
                "http://localhost:39999/unreachable", "GET", null, null))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void execute_shouldIncludeJsonAcceptHeader() throws Exception {
        try (MockHttpServer server = new MockHttpServer()) {
            server.when("/api/data", "GET", 200, "{}")
                  .start();

            DirectHttpExecutor executor = new DirectHttpExecutor();
            executor.execute(server.getBaseUrl() + "/api/data", "GET", null, null);

            MockHttpServer.RecordedRequest req = server.findRequest("GET", "/api/data");
            assertThat(req).isNotNull();
            assertThat(req.header("Accept")).contains("application/json");
        }
    }
}
