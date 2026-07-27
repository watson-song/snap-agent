package cn.watsontech.snapagent.boot2x.issue;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import cn.watsontech.snapagent.core.issue.IssueTracker;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GitHub Issues tracker implementation.
 *
 * <p>Uses the GitHub REST API v3 to create issues, change their state
 * (open/closed), and generate web URLs. Authentication is via a Personal
 * Access Token (classic or fine-grained) passed in the
 * {@code Authorization: Bearer {token}} header.</p>
 *
 * <p>Configuration (under {@code snap-agent.issue-closure.github}):</p>
 * <ul>
 *   <li>{@code api-base-url} — default {@code https://api.github.com}</li>
 *   <li>{@code token}  — GitHub PAT</li>
 *   <li>{@code owner}  — repository owner (user or org)</li>
 *   <li>{@code repo}   — repository name</li>
 * </ul>
 *
 * <h3>API mapping</h3>
 * <table border="1">
 * <tr><th>SPI method</th><th>GitHub endpoint</th></tr>
 * <tr><td>createIssue</td><td>POST /repos/{owner}/{repo}/issues</td></tr>
 * <tr><td>updateStatus</td><td>PATCH /repos/{owner}/{repo}/issues/{number}</td></tr>
 * <tr><td>getIssueUrl</td><td>https://github.com/{owner}/{repo}/issues/{number}</td></tr>
 * </table>
 */
public class GitHubIssueTracker extends AbstractHttpIssueTracker implements IssueTracker {

    private final String apiBaseUrl;
    private final String token;
    private final String owner;
    private final String repo;

    public GitHubIssueTracker(SnapAgentProperties.IssueClosure.GitHubTracker config) {
        this.apiBaseUrl = trimSlash(config.getApiBaseUrl());
        this.token = config.getToken();
        this.owner = config.getOwner();
        this.repo = config.getRepo();
    }

    @Override
    public String createIssue(String title, String description, String assignee) {
        // POST /repos/{owner}/{repo}/issues
        String url = apiBaseUrl + "/repos/" + owner + "/" + repo + "/issues";

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("title", title != null ? title : "");
        if (description != null && !description.isEmpty()) {
            body.put("body", description);
        }
        if (assignee != null && !assignee.isEmpty()) {
            List<String> assignees = new ArrayList<String>();
            assignees.add(assignee);
            body.put("assignees", assignees);
        }
        // Tag as automated diagnostic issue
        List<String> labels = new ArrayList<String>();
        labels.add("snap-agent");
        body.put("labels", labels);

        JsonNode resp = jsonRequest(url, "POST", authHeader("Bearer " + token), body);
        // GitHub returns {"number": 42, "html_url": "..."}
        if (resp != null && resp.has("number")) {
            return String.valueOf(resp.get("number").asInt());
        }
        return null;
    }

    @Override
    public void updateStatus(String externalIssueId, String status) {
        if (externalIssueId == null || externalIssueId.isEmpty()) {
            return;
        }

        // PATCH /repos/{owner}/{repo}/issues/{number}
        String url = apiBaseUrl + "/repos/" + owner + "/" + repo + "/issues/" + externalIssueId;

        String state = resolveState(status);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("state", state);

        jsonRequest(url, "PATCH", authHeader("Bearer " + token), body);
    }

    @Override
    public String getIssueUrl(String externalIssueId) {
        if (externalIssueId == null || externalIssueId.isEmpty()) {
            return null;
        }
        // Prefer the web URL format over API URL
        if ("https://api.github.com".equals(apiBaseUrl)) {
            return "https://github.com/" + owner + "/" + repo + "/issues/" + externalIssueId;
        }
        // For GitHub Enterprise, derive web URL from API base
        // apiBaseUrl is like https://github.example.com/api/v3
        String webBase = apiBaseUrl;
        int apiIdx = webBase.indexOf("/api/v");
        if (apiIdx > 0) {
            webBase = webBase.substring(0, apiIdx);
        }
        return webBase + "/" + owner + "/" + repo + "/issues/" + externalIssueId;
    }

    @Override
    public String type() {
        return "github";
    }

    /**
     * Maps a SnapAgent status string to a GitHub issue state.
     */
    private String resolveState(String status) {
        if (status == null) {
            return "open";
        }
        String lower = status.toLowerCase();
        if (lower.contains("close") || lower.contains("resolved")
                || lower.contains("fixed") || lower.contains("verified")) {
            return "closed";
        }
        if (lower.contains("reopen") || lower.contains("active")) {
            return "open";
        }
        return "open";
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
