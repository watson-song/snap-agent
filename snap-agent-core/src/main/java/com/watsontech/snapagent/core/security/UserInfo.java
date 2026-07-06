package com.watsontech.snapagent.core.security;

/**
 * User information returned by the {@code GET /user-info} endpoint.
 *
 * <p>This is a simple POJO serialized as JSON by the controller.</p>
 */
public class UserInfo {

    private boolean authenticated;
    private boolean authorized;
    private String userId;
    private String username;
    private String message;

    public UserInfo() {
    }

    public UserInfo(boolean authenticated, boolean authorized, String userId, String username, String message) {
        this.authenticated = authenticated;
        this.authorized = authorized;
        this.userId = userId;
        this.username = username;
        this.message = message;
    }

    public boolean isAuthenticated() {
        return authenticated;
    }

    public void setAuthenticated(boolean authenticated) {
        this.authenticated = authenticated;
    }

    public boolean isAuthorized() {
        return authorized;
    }

    public void setAuthorized(boolean authorized) {
        this.authorized = authorized;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
