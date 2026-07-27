package cn.watsontech.snapagent.boot2x.issue;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import cn.watsontech.snapagent.core.issue.IssueTracker;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Zentao (禅道) issue tracker implementation.
 *
 * <p>Uses the Zentao REST API v1 to create bugs, resolve/close them,
 * and generate web URLs. Authentication is via a Personal Access Token
 * (PAT) passed in the {@code Token} header.</p>
 *
 * <p>Configuration (under {@code snap-agent.issue-closure.zentao}):</p>
 * <ul>
 *   <li>{@code base-url} — e.g. {@code https://zentao.example.com}</li>
 *   <li>{@code token}    — Zentao PAT or session token</li>
 *   <li>{@code product-id} — numeric product ID for bug creation</li>
 *   <li>{@code project-id} — optional numeric project ID</li>
 * </ul>
 *
 * <h3>API mapping</h3>
 * <table border="1">
 * <tr><th>SPI method</th><th>Zentao endpoint</th></tr>
 * <tr><td>createIssue</td><td>POST /api.php/v1/products/{productId}/bugs</td></tr>
 * <tr><td>updateStatus</td><td>POST /api.php/v1/bugs/{id}/resolve | /close | /activate</td></tr>
 * <tr><td>getIssueUrl</td><td>{base-url}/bug-view-{id}.html</td></tr>
 * </table>
 */
public class ZentaoIssueTracker extends AbstractHttpIssueTracker implements IssueTracker {

    private final String baseUrl;
    private final String token;
    private final int productId;
    private final int projectId;

    public ZentaoIssueTracker(SnapAgentProperties.IssueClosure.ZentaoTracker config) {
        this.baseUrl = trimSlash(config.getBaseUrl());
        this.token = config.getToken();
        this.productId = config.getProductId();
        this.projectId = config.getProjectId();
    }

    @Override
    public String createIssue(String title, String description, String assignee) {
        // POST /api.php/v1/products/{productId}/bugs
        String url = baseUrl + "/api.php/v1/products/" + productId + "/bugs";

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("title", title != null ? title : "");
        body.put("desc", description != null ? description : "");
        body.put("severity", 3);   // 1-4, 3 = normal
        body.put("pri", 3);         // 1-4, 3 = medium
        body.put("type", "codeerror");
        if (projectId > 0) {
            body.put("project", projectId);
        }
        if (assignee != null && !assignee.isEmpty()) {
            body.put("assignedTo", assignee);
        }

        JsonNode resp = jsonRequest(url, "POST", authHeader("Token " + token), body);
        // Zentao returns {"id": 123} on success
        if (resp != null && resp.has("id")) {
            return String.valueOf(resp.get("id").asInt());
        }
        return null;
    }

    @Override
    public void updateStatus(String externalIssueId, String status) {
        if (externalIssueId == null || externalIssueId.isEmpty()) {
            return;
        }

        String action = resolveAction(status);
        String url = baseUrl + "/api.php/v1/bugs/" + externalIssueId + "/" + action;

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        if ("resolve".equals(action)) {
            body.put("resolution", "fixed");
            body.put("resolvedBy", "");
        }

        jsonRequest(url, "POST", authHeader("Token " + token), body.isEmpty() ? null : body);
    }

    @Override
    public String getIssueUrl(String externalIssueId) {
        if (externalIssueId == null || externalIssueId.isEmpty()) {
            return null;
        }
        return baseUrl + "/bug-view-" + externalIssueId + ".html";
    }

    @Override
    public String type() {
        return "zentao";
    }

    @Override
    public void addComment(String externalIssueId, String comment) {
        if (externalIssueId == null || externalIssueId.isEmpty()) {
            return;
        }

        String url = baseUrl + "/api.php/v1/bugs/" + externalIssueId + "/comments";
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("comment", comment != null ? comment : "");

        jsonRequest(url, "POST", authHeader("Token " + token), body);
    }

    /**
     * Maps a SnapAgent status string to a Zentao bug action.
     *
     * <ul>
     *   <li>resolved / closed / fixed → resolve (then close)</li>
     *   <li>open / active / reopened → activate</li>
     *   <li>close → close</li>
     * </ul>
     */
    private String resolveAction(String status) {
        if (status == null) {
            return "resolve";
        }
        String lower = status.toLowerCase();
        if (lower.contains("close") || lower.contains("closed")) {
            return "close";
        }
        if (lower.contains("active") || lower.contains("reopen") || lower.contains("open")) {
            return "activate";
        }
        // resolved, fixed, verified, etc.
        return "resolve";
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
