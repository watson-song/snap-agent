package cn.watsontech.snapagent.boot2x.issue;

import java.util.Map;

/**
 * Result payload received from the frontend via {@code POST /bridge/result}.
 */
public class BridgeResponse {

    private String id;
    private int status;
    private String body;
    private Map<String, String> headers;
    private String error;

    public BridgeResponse() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public int getStatus() { return status; }
    public void setStatus(int status) { this.status = status; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public Map<String, String> getHeaders() { return headers; }
    public void setHeaders(Map<String, String> headers) { this.headers = headers; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
}
