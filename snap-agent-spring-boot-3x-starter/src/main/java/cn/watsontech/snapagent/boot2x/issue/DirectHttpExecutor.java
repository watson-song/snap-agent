package cn.watsontech.snapagent.boot2x.issue;

import cn.watsontech.snapagent.core.issue.HttpExecutor;
import cn.watsontech.snapagent.core.issue.HttpResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Default {@link HttpExecutor} implementation using {@link HttpURLConnection}.
 *
 * <p>Encapsulates the HTTP execution logic previously inlined in
 * {@code AbstractHttpIssueTracker.jsonRequest()} and
 * {@code AbstractHttpVcsClient.jsonRequest()}. When the bridge is disabled
 * (the default), this executor is used, producing byte-identical behavior
 * to the pre-bridge code.</p>
 */
public class DirectHttpExecutor implements HttpExecutor {

    private static final Logger log = LoggerFactory.getLogger(DirectHttpExecutor.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public HttpResponse execute(String urlStr, String method,
                                Map<String, String> headers, Object body) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(true);

            // Always send/receive JSON
            conn.setRequestProperty("Accept", "application/json");

            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }

            if (body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                String json = objectMapper.writeValueAsString(body);
                byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                conn.setRequestProperty("Content-Length", String.valueOf(bytes.length));
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bytes);
                    os.flush();
                }
            }

            int code = conn.getResponseCode();
            String responseBody = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());

            if (code >= 400) {
                throw new RuntimeException(
                        "HTTP " + code + " from " + method + " " + urlStr + ": " + responseBody);
            }

            return new HttpResponse(code, responseBody);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed " + method + " " + urlStr + ": " + e.getMessage(), e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private String readAll(InputStream is) {
        if (is == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(is, StandardCharsets.UTF_8));
        String line;
        try {
            while ((line = reader.readLine()) != null) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(line);
            }
        } catch (Exception e) {
            log.warn("Failed reading response stream: {}", e.getMessage());
        }
        return sb.toString();
    }
}
