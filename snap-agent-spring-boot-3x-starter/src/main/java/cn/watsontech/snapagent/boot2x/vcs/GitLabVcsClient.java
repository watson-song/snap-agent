package cn.watsontech.snapagent.boot2x.vcs;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import cn.watsontech.snapagent.core.vcs.FileChange;
import cn.watsontech.snapagent.core.vcs.MergeRequestInfo;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class GitLabVcsClient extends AbstractHttpVcsClient {

    private final String baseUrl;
    private final String token;
    private final int projectId;

    public GitLabVcsClient(SnapAgentProperties.Vcs.GitLab config) {
        this.baseUrl = trimSlash(config.getBaseUrl());
        this.token = config.getToken();
        this.projectId = config.getProjectId();
    }

    @Override
    public void createBranch(String branchName) {
        String url = baseUrl + "/api/v4/projects/" + projectId + "/repository/branches";
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("branch", branchName);
        body.put("ref", "main");
        jsonRequest(url, "POST", authHeader("PRIVATE-TOKEN", token), body);
    }

    @Override
    public String commitFiles(String branchName, List<FileChange> changes, String commitMessage) {
        if (changes == null || changes.isEmpty()) {
            throw new RuntimeException("No file changes to commit");
        }
        String url = baseUrl + "/api/v4/projects/" + projectId + "/repository/commits";
        List<Map<String, Object>> actions = new ArrayList<Map<String, Object>>();
        for (FileChange fc : changes) {
            Map<String, Object> action = new LinkedHashMap<String, Object>();
            action.put("action", fc.getAction().toLowerCase());
            action.put("file_path", fc.getFilePath());
            if (fc.getContent() != null) {
                action.put("content", fc.getContent());
            }
            actions.add(action);
        }
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("branch", branchName);
        body.put("commit_message", commitMessage);
        body.put("actions", actions);

        JsonNode resp = jsonRequest(url, "POST", authHeader("PRIVATE-TOKEN", token), body);
        if (resp != null && resp.has("id")) {
            return resp.get("id").asText();
        }
        return null;
    }

    @Override
    public MergeRequestInfo createPullRequest(String sourceBranch, String targetBranch,
                                              String title, String description) {
        String url = baseUrl + "/api/v4/projects/" + projectId + "/merge_requests";
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("source_branch", sourceBranch);
        body.put("target_branch", targetBranch);
        body.put("title", title);
        body.put("description", description != null ? description : "");

        JsonNode resp = jsonRequest(url, "POST", authHeader("PRIVATE-TOKEN", token), body);
        if (resp != null) {
            String prUrl = resp.has("web_url") ? resp.get("web_url").asText() : null;
            String prNumber = resp.has("iid") ? String.valueOf(resp.get("iid").asInt()) : null;
            String commitSha = resp.has("sha") ? resp.get("sha").asText() : null;
            return new MergeRequestInfo(prUrl, prNumber, commitSha);
        }
        return new MergeRequestInfo(null, null, null);
    }

    @Override
    public String getMergeStatus(String prNumber) {
        String url = baseUrl + "/api/v4/projects/" + projectId + "/merge_requests/" + prNumber;
        JsonNode resp = jsonRequest(url, "GET", authHeader("PRIVATE-TOKEN", token), null);
        if (resp != null && resp.has("state")) {
            return resp.get("state").asText().toUpperCase();
        }
        return "UNKNOWN";
    }

    @Override
    public String type() {
        return "gitlab";
    }
}
