package cn.watsontech.snapagent.boot2x.vcs;

import cn.watsontech.snapagent.core.vcs.VcsClient;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP base class for VcsClient implementations.
 * Provides JSON HTTP request helper using HttpURLConnection + Jackson.
 */
abstract class AbstractHttpVcsClient implements VcsClient {

    protected static final Logger log = LoggerFactory.getLogger(AbstractHttpVcsClient.class);
    protected final ObjectMapper objectMapper = new ObjectMapper();

    protected JsonNode jsonRequest(String urlStr, String method,
                                   Map<String, String> headers, Object body) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(true);
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
                throw new RuntimeException("HTTP " + code + " from " + method + " " + urlStr + ": " + responseBody);
            }

            if (responseBody == null || responseBody.isEmpty()) {
                return null;
            }
            return objectMapper.readTree(responseBody);
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

    protected static Map<String, String> authHeader(String header, String value) {
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put(header, value);
        return h;
    }

    protected String readAll(InputStream is) {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        String line;
        try {
            while ((line = reader.readLine()) != null) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(line);
            }
        } catch (Exception e) {
            log.warn("Failed reading response: {}", e.getMessage());
        }
        return sb.toString();
    }

    protected static String trimSlash(String s) {
        if (s == null) return "";
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
