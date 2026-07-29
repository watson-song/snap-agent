package cn.watsontech.snapagent.boot2x.issue;

import java.util.Map;

/**
 * Request payload pushed to the frontend via SSE {@code proxy-request} event.
 *
 * <p>The frontend dispatches the HTTP request through the Chrome Extension
 * (or direct fetch) and POSTs the result back via {@code /bridge/result}.</p>
 */
public class BridgeRequest {

    private String id;
    private String serviceType;
    private String url;
    private String method;
    private Map<String, String> headers;
    private Object body;

    public BridgeRequest() {
    }

    public BridgeRequest(String id, String serviceType, String url,
                         String method, Map<String, String> headers, Object body) {
        this.id = id;
        this.serviceType = serviceType;
        this.url = url;
        this.method = method;
        this.headers = headers;
        this.body = body;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getServiceType() { return serviceType; }
    public void setServiceType(String serviceType) { this.serviceType = serviceType; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }
    public Map<String, String> getHeaders() { return headers; }
    public void setHeaders(Map<String, String> headers) { this.headers = headers; }
    public Object getBody() { return body; }
    public void setBody(Object body) { this.body = body; }
}
