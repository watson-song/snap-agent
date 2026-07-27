package cn.watsontech.snapagent.boot2x.issue;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import cn.watsontech.snapagent.core.issue.IssueTracker;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Jira issue tracker implementation.
 *
 * <p>Uses the Jira REST API v2 to create issues, transition their status,
 * and generate web URLs. Supports both Jira Cloud (email + API token with
 * Basic Auth) and Jira Server/Data Center (personal token with Bearer).</p>
 *
 * <p>Configuration (under {@code snap-agent.issue-closure.jira}):</p>
 * <ul>
 *   <li>{@code base-url}    — e.g. {@code https://myteam.atlassian.net}</li>
 *   <li>{@code username}    — email (Cloud) or username (Server); leave empty for Bearer-only</li>
 *   <li>{@code api-token}   — API token (Cloud) or PAT (Server)</li>
 *   <li>{@code project-key} — Jira project key, e.g. {@code PROJ}</li>
 *   <li>{@code issue-type}  — issue type name, default {@code Bug}</li>
 * </ul>
 *
 * <h3>API mapping</h3>
 * <table border="1">
 * <tr><th>SPI method</th><th>Jira endpoint</th></tr>
 * <tr><td>createIssue</td><td>POST /rest/api/2/issue</td></tr>
 * <tr><td>updateStatus</td><td>POST /rest/api/2/issue/{key}/transitions</td></tr>
 * <tr><td>getIssueUrl</td><td>{base-url}/browse/{key}</td></tr>
 * </table>
 */
public class JiraIssueTracker extends AbstractHttpIssueTracker implements IssueTracker {

    private final String baseUrl;
    private final String username;
    private final String apiToken;
    private final String projectKey;
    private final String issueType;

    public JiraIssueTracker(SnapAgentProperties.IssueClosure.JiraTracker config) {
        this.baseUrl = trimSlash(config.getBaseUrl());
        this.username = config.getUsername();
        this.apiToken = config.getApiToken();
        this.projectKey = config.getProjectKey();
        this.issueType = config.getIssueType() != null && !config.getIssueType().isEmpty()
                ? config.getIssueType() : "Bug";
    }

    @Override
    public String createIssue(String title, String description, String assignee) {
        // POST /rest/api/2/issue
        String url = baseUrl + "/rest/api/2/issue";

        // Build fields map
        Map<String, Object> fields = new LinkedHashMap<String, Object>();
        Map<String, Object> project = new LinkedHashMap<String, Object>();
        project.put("key", projectKey);
        fields.put("project", project);

        fields.put("summary", title != null ? title : "");

        if (description != null && !description.isEmpty()) {
            fields.put("description", description);
        }

        Map<String, Object> issueTypeMap = new LinkedHashMap<String, Object>();
        issueTypeMap.put("name", issueType);
        fields.put("issuetype", issueTypeMap);

        if (assignee != null && !assignee.isEmpty()) {
            Map<String, Object> assigneeMap = new LinkedHashMap<String, Object>();
            assigneeMap.put("name", assignee);
            fields.put("assignee", assigneeMap);
        }

        // Labels
        java.util.List<String> labels = new java.util.ArrayList<String>();
        labels.add("snap-agent");
        fields.put("labels", labels);

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("fields", fields);

        JsonNode resp = jsonRequest(url, "POST", authHeaders(), body);
        // Jira returns {"id":"10001","key":"PROJ-123","self":"..."}
        if (resp != null && resp.has("key")) {
            return resp.get("key").asText();
        }
        return null;
    }

    @Override
    public void updateStatus(String externalIssueId, String status) {
        if (externalIssueId == null || externalIssueId.isEmpty()) {
            return;
        }

        // First, get available transitions
        String transitionsUrl = baseUrl + "/rest/api/2/issue/" + externalIssueId + "/transitions";
        JsonNode transitionsResp = jsonRequest(transitionsUrl, "GET", authHeaders(), null);

        if (transitionsResp == null || !transitionsResp.has("transitions")) {
            return;
        }

        String desiredTransition = resolveTransitionName(status);
        String transitionId = findTransitionId(transitionsResp.get("transitions"), desiredTransition);

        if (transitionId == null) {
            // Try a broader match
            transitionId = findTransitionId(transitionsResp.get("transitions"), null);
        }

        if (transitionId == null) {
            return;
        }

        // POST /rest/api/2/issue/{key}/transitions
        String url = baseUrl + "/rest/api/2/issue/" + externalIssueId + "/transitions";
        Map<String, Object> transition = new LinkedHashMap<String, Object>();
        transition.put("id", transitionId);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("transition", transition);

        jsonRequest(url, "POST", authHeaders(), body);
    }

    @Override
    public String getIssueUrl(String externalIssueId) {
        if (externalIssueId == null || externalIssueId.isEmpty()) {
            return null;
        }
        return baseUrl + "/browse/" + externalIssueId;
    }

    @Override
    public String type() {
        return "jira";
    }

    /**
     * Returns the appropriate Authorization header value.
     *
     * <p>When {@code username} is set, uses Basic Auth with username:apiToken.
     * When {@code username} is empty, uses Bearer token (Jira Server/DC PAT).</p>
     */
    private Map<String, String> authHeaders() {
        if (username != null && !username.isEmpty()) {
            String credentials = username + ":" + apiToken;
            String encoded = Base64.getEncoder()
                    .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
            return authHeader("Basic " + encoded);
        }
        return authHeader("Bearer " + apiToken);
    }

    /**
     * Maps a SnapAgent status string to a Jira transition name.
     */
    private String resolveTransitionName(String status) {
        if (status == null) {
            return "Resolved";
        }
        String lower = status.toLowerCase();
        if (lower.contains("close") || lower.contains("closed")) {
            return "Closed";
        }
        if (lower.contains("resolve") || lower.contains("fixed")
                || lower.contains("verified")) {
            return "Resolved";
        }
        if (lower.contains("reopen") || lower.contains("active")
                || lower.contains("open")) {
            return "Reopen";
        }
        if (lower.contains("progress") || lower.contains("start")) {
            return "In Progress";
        }
        return "Resolved";
    }

    /**
     * Finds a transition ID by name. When {@code desiredName} is {@code null},
     * returns the first available transition ID (fallback).
     */
    private String findTransitionId(JsonNode transitions, String desiredName) {
        if (transitions == null || !transitions.isArray()) {
            return null;
        }
        String fallbackId = null;
        for (JsonNode t : transitions) {
            String id = t.has("id") ? t.get("id").asText() : null;
            String name = t.has("name") ? t.get("name").asText() : "";
            if (desiredName != null && desiredName.equalsIgnoreCase(name)) {
                return id;
            }
            if (fallbackId == null && id != null) {
                fallbackId = id;
            }
        }
        return fallbackId;
    }

    private static String trimSlash(String s) {
        if (s == null) {
            return "";
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
