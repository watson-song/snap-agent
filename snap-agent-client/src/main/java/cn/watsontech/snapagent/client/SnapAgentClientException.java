package cn.watsontech.snapagent.client;

public class SnapAgentClientException extends RuntimeException {
    private final int statusCode;

    public SnapAgentClientException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public SnapAgentClientException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
