package cn.watsontech.snapagent.standalone.settings;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Runtime configuration for snap-agent standalone.
 * Persisted as YAML to disk. Supports hot-reload for LLM/DB/features.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Settings {

    private LlmSettings llm = new LlmSettings();
    private JdbcSettings jdbc = new JdbcSettings();
    private SecuritySettings security = new SecuritySettings();
    private FeatureSettings features = new FeatureSettings();

    public LlmSettings getLlm() { return llm; }
    public void setLlm(LlmSettings llm) { this.llm = llm; }
    public JdbcSettings getJdbc() { return jdbc; }
    public void setJdbc(JdbcSettings jdbc) { this.jdbc = jdbc; }
    public SecuritySettings getSecurity() { return security; }
    public void setSecurity(SecuritySettings security) { this.security = security; }
    public FeatureSettings getFeatures() { return features; }
    public void setFeatures(FeatureSettings features) { this.features = features; }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LlmSettings {
        private String apiType = "anthropic";
        private String baseUrl = "https://api.anthropic.com";
        private String apiKey = "";
        private String authToken = "";
        private String model = "claude-sonnet-4-20250514";
        private int maxTokens = 8192;
        private int timeoutSeconds = 120;

        public String getApiType() { return apiType; }
        public void setApiType(String apiType) { this.apiType = apiType; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getAuthToken() { return authToken; }
        public void setAuthToken(String authToken) { this.authToken = authToken; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JdbcSettings {
        private boolean enabled = false;
        private String url = "";
        private String username = "";
        private String password = "";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SecuritySettings {
        private String jwtSecret = "";
        private String corsAllowedOrigins = "*";
        private String userClaim = "sub";

        public String getJwtSecret() { return jwtSecret; }
        public void setJwtSecret(String jwtSecret) { this.jwtSecret = jwtSecret; }
        public String getCorsAllowedOrigins() { return corsAllowedOrigins; }
        public void setCorsAllowedOrigins(String corsAllowedOrigins) { this.corsAllowedOrigins = corsAllowedOrigins; }
        public String getUserClaim() { return userClaim; }
        public void setUserClaim(String userClaim) { this.userClaim = userClaim; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FeatureSettings {
        private boolean jdbcEnabled = false;
        private boolean anchorEnabled = false;
        private boolean patrolEnabled = false;
        private boolean alertEnabled = false;
        private boolean codeGraphEnabled = false;

        public boolean isJdbcEnabled() { return jdbcEnabled; }
        public void setJdbcEnabled(boolean jdbcEnabled) { this.jdbcEnabled = jdbcEnabled; }
        public boolean isAnchorEnabled() { return anchorEnabled; }
        public void setAnchorEnabled(boolean anchorEnabled) { this.anchorEnabled = anchorEnabled; }
        public boolean isPatrolEnabled() { return patrolEnabled; }
        public void setPatrolEnabled(boolean patrolEnabled) { this.patrolEnabled = patrolEnabled; }
        public boolean isAlertEnabled() { return alertEnabled; }
        public void setAlertEnabled(boolean alertEnabled) { this.alertEnabled = alertEnabled; }
        public boolean isCodeGraphEnabled() { return codeGraphEnabled; }
        public void setCodeGraphEnabled(boolean codeGraphEnabled) { this.codeGraphEnabled = codeGraphEnabled; }
    }
}
