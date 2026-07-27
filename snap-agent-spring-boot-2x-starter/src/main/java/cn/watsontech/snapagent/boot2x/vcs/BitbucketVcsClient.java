package cn.watsontech.snapagent.boot2x.vcs;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import cn.watsontech.snapagent.core.vcs.FileChange;
import cn.watsontech.snapagent.core.vcs.MergeRequestInfo;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class BitbucketVcsClient extends AbstractHttpVcsClient {

    private final String baseUrl;
    private final String token;
    private final String projectKey;
    private final String repoSlug;

    public BitbucketVcsClient(SnapAgentProperties.Vcs.Bitbucket config) {
        this.baseUrl = trimSlash(config.getBaseUrl());
        this.token = config.getToken();
        this.projectKey = config.getProjectKey();
        this.repoSlug = config.getRepoSlug();
    }

    @Override
    public void createBranch(String branchName) {
        String url = baseUrl + "/rest/branch-utils/1.0/projects/" + projectKey
                + "/repos/" + repoSlug + "/branches";
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("name", branchName);
        body.put("startPoint", "refs/heads/main");
        jsonRequest(url, "POST", authHeader("Authorization", "Bearer " + token), body);
    }

    @Override
    public String commitFiles(String branchName, List<FileChange> changes, String commitMessage) {
        String lastCommitSha = null;
        for (FileChange fc : changes) {
            String encodedPath;
            String encodedBranch;
            String encodedMessage;
            try {
                encodedPath = URLEncoder.encode(fc.getFilePath(), "UTF-8").replace("%2F", "/");
                encodedBranch = URLEncoder.encode(branchName, "UTF-8");
                encodedMessage = URLEncoder.encode(commitMessage, "UTF-8");
            } catch (java.io.UnsupportedEncodingException e) {
                throw new RuntimeException("UTF-8 not supported", e);
            }
            String url = baseUrl + "/rest/api/1.0/projects/" + projectKey
                    + "/repos/" + repoSlug + "/browse/" + encodedPath
                    + "?branch=" + encodedBranch
                    + "&message=" + encodedMessage;

            lastCommitSha = putFileContent(url, fc.getContent());
        }
        return lastCommitSha;
    }

    private String putFileContent(String urlStr, String content) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("PUT");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");

            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("content", content != null ? content : "");
            String json = objectMapper.writeValueAsString(body);
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(bytes);
                os.flush();
            }

            int code = conn.getResponseCode();
            String respBody = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (code >= 400) {
                throw new RuntimeException("HTTP " + code + ": " + respBody);
            }
            if (!respBody.isEmpty()) {
                JsonNode node = objectMapper.readTree(respBody);
                if (node.has("id")) return node.get("id").asText();
            }
            return null;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed PUT: " + e.getMessage(), e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    @Override
    public MergeRequestInfo createPullRequest(String sourceBranch, String targetBranch,
                                              String title, String description) {
        String url = baseUrl + "/rest/api/1.0/projects/" + projectKey
                + "/repos/" + repoSlug + "/pull-requests";
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("title", title);
        body.put("description", description != null ? description : "");

        Map<String, Object> fromRef = new LinkedHashMap<String, Object>();
        fromRef.put("id", "refs/heads/" + sourceBranch);
        Map<String, Object> fromRepo = new LinkedHashMap<String, Object>();
        fromRepo.put("slug", repoSlug);
        fromRef.put("repository", fromRepo);
        body.put("fromRef", fromRef);

        Map<String, Object> toRef = new LinkedHashMap<String, Object>();
        toRef.put("id", "refs/heads/" + targetBranch);
        Map<String, Object> toRepo = new LinkedHashMap<String, Object>();
        toRepo.put("slug", repoSlug);
        toRef.put("repository", toRepo);
        body.put("toRef", toRef);

        JsonNode resp = jsonRequest(url, "POST", authHeader("Authorization", "Bearer " + token), body);
        if (resp != null) {
            int prId = resp.has("id") ? resp.get("id").asInt() : 0;
            String prUrl = baseUrl + "/projects/" + projectKey
                    + "/repos/" + repoSlug + "/pull-requests/" + prId;
            String commitSha = resp.has("fromRef") && resp.get("fromRef").has("latestCommit")
                    ? resp.get("fromRef").get("latestCommit").asText() : null;
            return new MergeRequestInfo(prUrl, String.valueOf(prId), commitSha);
        }
        return new MergeRequestInfo(null, null, null);
    }

    @Override
    public String getMergeStatus(String prNumber) {
        String url = baseUrl + "/rest/api/1.0/projects/" + projectKey
                + "/repos/" + repoSlug + "/pull-requests/" + prNumber;
        JsonNode resp = jsonRequest(url, "GET", authHeader("Authorization", "Bearer " + token), null);
        if (resp != null && resp.has("state")) {
            return resp.get("state").asText().toUpperCase();
        }
        return "UNKNOWN";
    }

    @Override
    public String type() {
        return "bitbucket";
    }
}
