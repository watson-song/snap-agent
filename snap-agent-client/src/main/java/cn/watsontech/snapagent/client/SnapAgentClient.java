package cn.watsontech.snapagent.client;

import cn.watsontech.snapagent.client.dto.SkillDto;
import cn.watsontech.snapagent.client.dto.TranscriptEventDto;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lightweight Java client for the SnapAgent REST API.
 *
 * <p>Uses HttpURLConnection — no external HTTP library required.
 * Supports Basic Auth or no-auth. For token-based auth, pass
 * the full Authorization header value via {@link #withAuthHeader(String)}.</p>
 */
public class SnapAgentClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final String authHeader;

    public SnapAgentClient(String baseUrl) {
        this(baseUrl, null);
    }

    public SnapAgentClient(String baseUrl, String username, String password) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
        String cred = username + ":" + password;
        this.authHeader = "Basic " + Base64.getEncoder().encodeToString(
                cred.getBytes(StandardCharsets.UTF_8));
    }

    private SnapAgentClient(String baseUrl, String authHeader) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.authHeader = authHeader;
    }

    /** Returns a new client with the specified Authorization header value. */
    public SnapAgentClient withAuthHeader(String header) {
        return new SnapAgentClient(this.baseUrl, header);
    }

    // ---- Skills ----

    /**
     * Lists all available skills.
     *
     * @return list of skill DTOs
     */
    @SuppressWarnings("unchecked")
    public List<SkillDto> listSkills() {
        Map<String, Object> response = httpGet("/skills", Map.class);
        Object skills = response != null ? response.get("skills") : null;
        if (skills == null) {
            return Collections.emptyList();
        }
        return MAPPER.convertValue(skills,
                MAPPER.getTypeFactory().constructCollectionType(List.class, SkillDto.class));
    }

    /**
     * Gets a single skill by name. Since the API does not have a per-skill endpoint,
     * this lists all skills and filters by name.
     *
     * @param name skill name
     * @return the skill DTO, or null if not found
     */
    public SkillDto getSkill(String name) {
        for (SkillDto skill : listSkills()) {
            if (name.equals(skill.getName())) {
                return skill;
            }
        }
        return null;
    }

    // ---- Runs ----

    /**
     * Starts a skill run and returns the task ID.
     *
     * @param skillName the skill identifier
     * @param inputs    map of input key to value
     * @return the task ID
     */
    public String runSkill(String skillName, Map<String, String> inputs) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("skillId", skillName);
        body.put("inputs", inputs != null ? inputs : Collections.emptyMap());
        Map<String, Object> result = httpPostJson("/runs", body);
        if (result == null) {
            return null;
        }
        Object taskId = result.get("taskId");
        if (taskId == null) {
            taskId = result.get("id");
        }
        return taskId != null ? taskId.toString() : null;
    }

    /**
     * Gets the status of a run.
     *
     * @param taskId the task ID
     * @return a map containing taskId, status, skillId, model, createdAt, updatedAt
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getRunStatus(String taskId) {
        return httpGet("/runs/" + taskId, Map.class);
    }

    /**
     * Gets the transcript of events for a run.
     *
     * @param taskId the task ID
     * @return list of transcript event DTOs
     */
    @SuppressWarnings("unchecked")
    public List<TranscriptEventDto> getTranscript(String taskId) {
        Map<String, Object> response = httpGet("/runs/" + taskId + "/transcript", Map.class);
        Object transcript = response != null ? response.get("transcript") : null;
        if (transcript == null) {
            return Collections.emptyList();
        }
        return MAPPER.convertValue(transcript,
                MAPPER.getTypeFactory().constructCollectionType(List.class, TranscriptEventDto.class));
    }

    /**
     * Cancels a running task.
     *
     * @param taskId the task ID
     */
    public void cancelRun(String taskId) {
        httpPostJson("/runs/" + taskId + "/cancel", Collections.emptyMap());
    }

    // ---- User Info ----

    /**
     * Gets user info for the authenticated user.
     *
     * @return a map containing user info fields
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getUserInfo() {
        return httpGet("/user-info", Map.class);
    }

    // ---- HTTP helpers ----

    /**
     * Opens an HttpURLConnection. Protected to allow test subclasses to override
     * and return mock connections.
     */
    protected HttpURLConnection openConnection(String method, String path, String contentType) throws IOException {
        URL url = new URL(baseUrl + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(60000);
        if (authHeader != null) {
            conn.setRequestProperty("Authorization", authHeader);
        }
        if (contentType != null) {
            conn.setRequestProperty("Content-Type", contentType);
            conn.setDoOutput(true);
        }
        conn.setRequestProperty("Accept", "application/json");
        return conn;
    }

    private <T> T httpGet(String path, Class<T> type) {
        HttpURLConnection conn = null;
        try {
            conn = openConnection("GET", path, null);
            int code = conn.getResponseCode();
            if (code >= 400) {
                String errBody = readError(conn);
                throw new SnapAgentClientException(
                        "GET " + path + " failed: HTTP " + code
                                + (errBody.isEmpty() ? "" : " " + errBody), code);
            }
            return parseResponse(conn, type);
        } catch (IOException e) {
            throw new SnapAgentClientException("GET " + path + " failed: " + e.getMessage(), -1, e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T httpPostJson(String path, Object body) {
        HttpURLConnection conn = null;
        try {
            conn = openConnection("POST", path, "application/json");
            String json = MAPPER.writeValueAsString(body);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            if (code >= 400) {
                String errBody = readError(conn);
                throw new SnapAgentClientException(
                        "POST " + path + " failed: HTTP " + code
                                + (errBody.isEmpty() ? "" : " " + errBody), code);
            }
            if (code == 204 || conn.getContentLength() == 0) {
                return null;
            }
            return (T) parseResponse(conn, Map.class);
        } catch (IOException e) {
            throw new SnapAgentClientException("POST " + path + " failed: " + e.getMessage(), -1, e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T parseResponse(HttpURLConnection conn, Class<T> type) throws IOException {
        InputStream is = conn.getInputStream();
        String body = readAll(is);
        if (type == null || type == Map.class) {
            return (T) MAPPER.readValue(body, Map.class);
        }
        return MAPPER.readValue(body, type);
    }

    private String readAll(InputStream is) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    private String readError(HttpURLConnection conn) {
        try {
            InputStream es = conn.getErrorStream();
            if (es != null) return readAll(es);
        } catch (IOException ignored) {
        }
        return "";
    }
}
