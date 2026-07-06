package com.watsontech.snapagent.boot2x.autoconfig;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration properties for the embedded SnapAgent ({@code prefix = "snap-agent"}).
 *
 * <p>Default {@code enabled=false} ensures zero impact when the starter is on
 * the classpath but not activated (TDD_SPEC §AC15).</p>
 *
 * <p>See design doc 07 §1 for the complete configuration tree.</p>
 */
@ConfigurationProperties(prefix = "snap-agent")
public class SnapAgentProperties {

    /** Master switch — when false, no beans are assembled. */
    private boolean enabled = false;

    /** Controller path prefix. */
    private String basePath = "/snap-agent";

    /** Directory on the classpath for built-in skills (read-only, packaged in JAR). */
    private String builtinSkillsDir = "classpath:/docs/skills/";

    /** Directory on the filesystem for uploaded skills (read-write, persists across restarts). */
    private String uploadSkillsDir = "/tmp/snap-agent-skills";

    private Llm llm = new Llm();
    private Agent agent = new Agent();
    private Jdbc jdbc = new Jdbc();
    private Redis redis = new Redis();
    private Logs logs = new Logs();
    private Mcp mcp = new Mcp();
    private Security security = new Security();
    private Routing routing = new Routing();

    // ---- getters / setters ----

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBasePath() {
        return basePath;
    }

    public void setBasePath(String basePath) {
        this.basePath = basePath;
    }

    public String getBuiltinSkillsDir() {
        return builtinSkillsDir;
    }

    public void setBuiltinSkillsDir(String builtinSkillsDir) {
        this.builtinSkillsDir = builtinSkillsDir;
    }

    public String getUploadSkillsDir() {
        return uploadSkillsDir;
    }

    public void setUploadSkillsDir(String uploadSkillsDir) {
        this.uploadSkillsDir = uploadSkillsDir;
    }

    public Llm getLlm() {
        return llm;
    }

    public void setLlm(Llm llm) {
        this.llm = llm;
    }

    public Agent getAgent() {
        return agent;
    }

    public void setAgent(Agent agent) {
        this.agent = agent;
    }

    public Jdbc getJdbc() {
        return jdbc;
    }

    public void setJdbc(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    public Redis getRedis() {
        return redis;
    }

    public void setRedis(Redis redis) {
        this.redis = redis;
    }

    public Logs getLogs() {
        return logs;
    }

    public void setLogs(Logs logs) {
        this.logs = logs;
    }

    public Mcp getMcp() {
        return mcp;
    }

    public void setMcp(Mcp mcp) {
        this.mcp = mcp;
    }

    public Security getSecurity() {
        return security;
    }

    public void setSecurity(Security security) {
        this.security = security;
    }

    public Routing getRouting() {
        return routing;
    }

    public void setRouting(Routing routing) {
        this.routing = routing;
    }

    // ---- nested classes ----

    /** LLM client configuration. */
    public static class Llm {
        private String baseUrl = "https://api.anthropic.com";
        private String apiKey = "";
        private String authToken = "";
        private String proxyUrl = "";
        private String model = "claude-sonnet-4-6";
        private List<String> allowedModels = new ArrayList<String>();
        private int maxTokens = 8192;
        private int timeoutSeconds = 120;
        private boolean streaming = true;

        public Llm() {
            allowedModels.add("claude-sonnet-4-6");
            allowedModels.add("claude-opus-4-6");
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getAuthToken() {
            return authToken;
        }

        public void setAuthToken(String authToken) {
            this.authToken = authToken;
        }

        public String getProxyUrl() {
            return proxyUrl;
        }

        public void setProxyUrl(String proxyUrl) {
            this.proxyUrl = proxyUrl;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public List<String> getAllowedModels() {
            return allowedModels;
        }

        public void setAllowedModels(List<String> allowedModels) {
            this.allowedModels = allowedModels;
        }

        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public boolean isStreaming() {
            return streaming;
        }

        public void setStreaming(boolean streaming) {
            this.streaming = streaming;
        }
    }

    /** Agent execution parameters. */
    public static class Agent {
        private int maxTurns = 20;
        private int taskTimeoutMinutes = 30;
        private String executor = "snapAgentExecutor";
        private int maxConcurrentRunsPerUser = 1;
        private int maxRunsPerHour = 20;
        private int maxResultRows = 1000;
        private int maxToolResultChars = 50000;
        private int transcriptEventLimit = 500;

        public int getMaxTurns() {
            return maxTurns;
        }

        public void setMaxTurns(int maxTurns) {
            this.maxTurns = maxTurns;
        }

        public int getTaskTimeoutMinutes() {
            return taskTimeoutMinutes;
        }

        public void setTaskTimeoutMinutes(int taskTimeoutMinutes) {
            this.taskTimeoutMinutes = taskTimeoutMinutes;
        }

        public String getExecutor() {
            return executor;
        }

        public void setExecutor(String executor) {
            this.executor = executor;
        }

        public int getMaxConcurrentRunsPerUser() {
            return maxConcurrentRunsPerUser;
        }

        public void setMaxConcurrentRunsPerUser(int maxConcurrentRunsPerUser) {
            this.maxConcurrentRunsPerUser = maxConcurrentRunsPerUser;
        }

        public int getMaxRunsPerHour() {
            return maxRunsPerHour;
        }

        public void setMaxRunsPerHour(int maxRunsPerHour) {
            this.maxRunsPerHour = maxRunsPerHour;
        }

        public int getMaxResultRows() {
            return maxResultRows;
        }

        public void setMaxResultRows(int maxResultRows) {
            this.maxResultRows = maxResultRows;
        }

        public int getMaxToolResultChars() {
            return maxToolResultChars;
        }

        public void setMaxToolResultChars(int maxToolResultChars) {
            this.maxToolResultChars = maxToolResultChars;
        }

        public int getTranscriptEventLimit() {
            return transcriptEventLimit;
        }

        public void setTranscriptEventLimit(int transcriptEventLimit) {
            this.transcriptEventLimit = transcriptEventLimit;
        }
    }

    /** JDBC read-only DataSource configuration. */
    public static class Jdbc {
        private boolean enabled = true;
        private String datasourceBeanName = "snapAgentReadOnlyDataSource";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getDatasourceBeanName() {
            return datasourceBeanName;
        }

        public void setDatasourceBeanName(String datasourceBeanName) {
            this.datasourceBeanName = datasourceBeanName;
        }
    }

    /** Redis read-only configuration. */
    public static class Redis {
        private boolean enabled = true;
        private String redisTemplateBeanName = "redisTemplate";
        private int maxKeyCount = 100;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getRedisTemplateBeanName() {
            return redisTemplateBeanName;
        }

        public void setRedisTemplateBeanName(String redisTemplateBeanName) {
            this.redisTemplateBeanName = redisTemplateBeanName;
        }

        public int getMaxKeyCount() {
            return maxKeyCount;
        }

        public void setMaxKeyCount(int maxKeyCount) {
            this.maxKeyCount = maxKeyCount;
        }
    }

    /** Log file analysis configuration. */
    public static class Logs {
        private boolean enabled = true;
        /** Directories under which log files may be read. */
        private List<String> allowedPaths = new ArrayList<String>();
        /** Max lines returned by a single log_read call. */
        private int maxLines = 500;
        /** Max file size in bytes (rejects larger files to prevent OOM). */
        private long maxFileBytes = 10L * 1024 * 1024; // 10 MB

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<String> getAllowedPaths() {
            return allowedPaths;
        }

        public void setAllowedPaths(List<String> allowedPaths) {
            this.allowedPaths = allowedPaths;
        }

        public int getMaxLines() {
            return maxLines;
        }

        public void setMaxLines(int maxLines) {
            this.maxLines = maxLines;
        }

        public long getMaxFileBytes() {
            return maxFileBytes;
        }

        public void setMaxFileBytes(long maxFileBytes) {
            this.maxFileBytes = maxFileBytes;
        }
    }

    /** MCP server configuration (Phase 2). */
    public static class Mcp {
        private boolean enabled = false;
        private Map<String, McpServer> servers = new LinkedHashMap<String, McpServer>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Map<String, McpServer> getServers() {
            return servers;
        }

        public void setServers(Map<String, McpServer> servers) {
            this.servers = servers;
        }
    }

    /** Individual MCP server entry. */
    public static class McpServer {
        private String transport;
        private String url;
        private String authHeader;
        private String authHeaderValue;

        public String getTransport() {
            return transport;
        }

        public void setTransport(String transport) {
            this.transport = transport;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getAuthHeader() {
            return authHeader;
        }

        public void setAuthHeader(String authHeader) {
            this.authHeader = authHeader;
        }

        public String getAuthHeaderValue() {
            return authHeaderValue;
        }

        public void setAuthHeaderValue(String authHeaderValue) {
            this.authHeaderValue = authHeaderValue;
        }
    }

    /**
     * Multi-instance routing configuration for peer discovery and SSE relay.
     *
     * <p>Discovery mode degradation chain: {@code k8s-api} → {@code headless-dns}
     * → {@code static} → {@code none} (sticky session fallback).</p>
     */
    public static class Routing {
        /** Discovery mode: k8s-api, headless-dns, static, none. */
        private String mode = "none";
        /** Shared secret for internal pod-to-pod endpoint auth. */
        private String internalToken = "";
        /** K8s Service name (for k8s-api / headless-dns modes). */
        private String k8sServiceName = "";
        /** Server port (auto-detected from server.port if 0). */
        private int port = 0;
        /** Static peer base-URLs (for static mode). */
        private List<String> staticPeers = new ArrayList<String>();
        /** Peer discovery result cache TTL in seconds. */
        private int discoveryCacheTtlSeconds = 10;
        /** Path where the internal pod-to-pod probe/stream endpoints are mounted. */
        private String internalPath = "/snap-agent-internal";
        /** Max peers to probe before giving up (limits blast radius). */
        private int maxPeersToProbe = 20;

        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
        public String getInternalToken() { return internalToken; }
        public void setInternalToken(String internalToken) { this.internalToken = internalToken; }
        public String getK8sServiceName() { return k8sServiceName; }
        public void setK8sServiceName(String k8sServiceName) { this.k8sServiceName = k8sServiceName; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public List<String> getStaticPeers() { return staticPeers; }
        public void setStaticPeers(List<String> staticPeers) { this.staticPeers = staticPeers; }
        public int getDiscoveryCacheTtlSeconds() { return discoveryCacheTtlSeconds; }
        public void setDiscoveryCacheTtlSeconds(int discoveryCacheTtlSeconds) { this.discoveryCacheTtlSeconds = discoveryCacheTtlSeconds; }
        public String getInternalPath() { return internalPath; }
        public void setInternalPath(String internalPath) { this.internalPath = internalPath; }
        public int getMaxPeersToProbe() { return maxPeersToProbe; }
        public void setMaxPeersToProbe(int maxPeersToProbe) { this.maxPeersToProbe = maxPeersToProbe; }
    }

    /** Security adapter configuration. */
    public static class Security {
        private String framework = "auto";
        private String requiredPermission = "";
        private int filterOrder = Integer.MAX_VALUE - 10;
        private String principalResolverClass = "";
        private boolean auditLog = true;

        public String getFramework() {
            return framework;
        }

        public void setFramework(String framework) {
            this.framework = framework;
        }

        public String getRequiredPermission() {
            return requiredPermission;
        }

        public void setRequiredPermission(String requiredPermission) {
            this.requiredPermission = requiredPermission;
        }

        public int getFilterOrder() {
            return filterOrder;
        }

        public void setFilterOrder(int filterOrder) {
            this.filterOrder = filterOrder;
        }

        public String getPrincipalResolverClass() {
            return principalResolverClass;
        }

        public void setPrincipalResolverClass(String principalResolverClass) {
            this.principalResolverClass = principalResolverClass;
        }

        public boolean isAuditLog() {
            return auditLog;
        }

        public void setAuditLog(boolean auditLog) {
            this.auditLog = auditLog;
        }
    }
}
