package cn.watsontech.snapagent.boot2x.autoconfig;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
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

    /**
     * Directory on the classpath for built-in skills (read-only, packaged in JAR).
     * <p>Uses {@code classpath*:} prefix to ensure resources from ALL classpath
     * roots are scanned (both the SnapAgent JAR and the host project). With
     * plain {@code classpath:}, Spring only resolves the first matching root,
     * which can shadow SnapAgent's built-in skills when the host project also
     * has {@code docs/skills/} on the classpath.</p>
     */
    private String builtinSkillsDir = "classpath*:/docs/skills/";

    /** Directory on the filesystem for uploaded skills (read-write, persists across restarts). */
    private String uploadSkillsDir = "/tmp/snap-agent-skills";

    /**
     * Host application's active Spring profiles (comma-joined), auto-resolved at startup.
     * Exposed to skills as {@code {_app_profile}} and surfaced in the web UI so the LLM
     * does not need to ask which environment it is operating on.
     */
    private String appProfiles = "";

    private Llm llm = new Llm();
    private Agent agent = new Agent();
    private Jdbc jdbc = new Jdbc();
    private Redis redis = new Redis();
    private Logs logs = new Logs();
    private Mcp mcp = new Mcp();
    private Security security = new Security();
    private Routing routing = new Routing();
    private Code code = new Code();
    private Metrics metrics = new Metrics();
    private LogSearch logSearch = new LogSearch();
    private Trace trace = new Trace();
    private ConfigRead configRead = new ConfigRead();
    private Patrol patrol = new Patrol();
    private Alert alert = new Alert();
    private Knowledge knowledge = new Knowledge();
    private CodeGraph codeGraph = new CodeGraph();
    private IssueClosure issueClosure = new IssueClosure();
    private Cost cost = new Cost();
    private Workflows workflows = new Workflows();
    private Skill skill = new Skill();
    private Anchor anchor = new Anchor();

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

    public String getAppProfiles() {
        return appProfiles;
    }

    public void setAppProfiles(String appProfiles) {
        this.appProfiles = appProfiles;
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

    public Code getCode() {
        return code;
    }

    public void setCode(Code code) {
        this.code = code;
    }

    public Metrics getMetrics() {
        return metrics;
    }

    public void setMetrics(Metrics metrics) {
        this.metrics = metrics;
    }

    public LogSearch getLogSearch() {
        return logSearch;
    }

    public void setLogSearch(LogSearch logSearch) {
        this.logSearch = logSearch;
    }

    public Trace getTrace() {
        return trace;
    }

    public void setTrace(Trace trace) {
        this.trace = trace;
    }

    public ConfigRead getConfigRead() {
        return configRead;
    }

    public void setConfigRead(ConfigRead configRead) {
        this.configRead = configRead;
    }

    public Patrol getPatrol() {
        return patrol;
    }

    public void setPatrol(Patrol patrol) {
        this.patrol = patrol;
    }

    public Alert getAlert() {
        return alert;
    }

    public void setAlert(Alert alert) {
        this.alert = alert;
    }

    public Knowledge getKnowledge() {
        return knowledge;
    }

    public void setKnowledge(Knowledge knowledge) {
        this.knowledge = knowledge;
    }

    public CodeGraph getCodeGraph() {
        return codeGraph;
    }

    public void setCodeGraph(CodeGraph codeGraph) {
        this.codeGraph = codeGraph;
    }

    public IssueClosure getIssueClosure() {
        return issueClosure;
    }

    public void setIssueClosure(IssueClosure issueClosure) {
        this.issueClosure = issueClosure;
    }

    public Cost getCost() {
        return cost;
    }

    public void setCost(Cost cost) {
        this.cost = cost;
    }

    public Workflows getWorkflows() {
        return workflows;
    }

    public void setWorkflows(Workflows workflows) {
        this.workflows = workflows;
    }

    public Skill getSkill() {
        return skill;
    }

    public void setSkill(Skill skill) {
        this.skill = skill;
    }

    public Anchor getAnchor() {
        return anchor;
    }

    public void setAnchor(Anchor anchor) {
        this.anchor = anchor;
    }

    // ---- nested classes ----

    /** LLM client configuration. */
    public static class Llm {
        /** API type: "anthropic" (default) or "openai" (OpenAI-compatible). */
        private String apiType = "anthropic";
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
        }

        public String getApiType() {
            return apiType;
        }

        public void setApiType(String apiType) {
            this.apiType = apiType;
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
        /** Multi-environment datasources (v0.6). When non-empty, overrides single-DataSource mode. */
        private Map<String, Datasource> datasources = new LinkedHashMap<String, Datasource>();
        /** Default environment name when the skill does not specify one. Empty = first entry. */
        private String defaultEnv = "";

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

        public Map<String, Datasource> getDatasources() {
            return datasources;
        }

        public void setDatasources(Map<String, Datasource> datasources) {
            this.datasources = datasources;
        }

        public String getDefaultEnv() {
            return defaultEnv;
        }

        public void setDefaultEnv(String defaultEnv) {
            this.defaultEnv = defaultEnv;
        }

        /** Individual multi-environment datasource entry (v0.6). */
        public static class Datasource {
            private String url;
            private String username = "";
            private String password = "";
            private String driverClassName = "com.mysql.cj.jdbc.Driver";

            public String getUrl() {
                return url;
            }

            public void setUrl(String url) {
                this.url = url;
            }

            public String getUsername() {
                return username;
            }

            public void setUsername(String username) {
                this.username = username;
            }

            public String getPassword() {
                return password;
            }

            public void setPassword(String password) {
                this.password = password;
            }

            public String getDriverClassName() {
                return driverClassName;
            }

            public void setDriverClassName(String driverClassName) {
                this.driverClassName = driverClassName;
            }
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
        /** Application's own log file path (auto-resolved from logging.file.name). */
        private String appLogFile;

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

        public String getAppLogFile() {
            return appLogFile;
        }

        public void setAppLogFile(String appLogFile) {
            this.appLogFile = appLogFile;
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
        private String requiredPermission = "snap-agent:access";
        private int filterOrder = Integer.MAX_VALUE - 10;
        private String principalResolverClass = "";
        private boolean auditLog = true;
        private String authTokenHeader = "";
        private String authTokenCookie = "";
        private String authTokenLocalStorageKey = "";

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

        public String getAuthTokenHeader() {
            return authTokenHeader;
        }

        public void setAuthTokenHeader(String authTokenHeader) {
            this.authTokenHeader = authTokenHeader;
        }

        public String getAuthTokenCookie() {
            return authTokenCookie;
        }

        public void setAuthTokenCookie(String authTokenCookie) {
            this.authTokenCookie = authTokenCookie;
        }

        public String getAuthTokenLocalStorageKey() {
            return authTokenLocalStorageKey;
        }

        public void setAuthTokenLocalStorageKey(String authTokenLocalStorageKey) {
            this.authTokenLocalStorageKey = authTokenLocalStorageKey;
        }
    }

    /**
     * Code understanding tool configuration (v0.3).
     *
     * <p>When {@code enabled=true} and {@code project-root} points to a valid
     * directory, three read-only tools ({@code code_read}, {@code project_structure},
     * {@code git_log}) are assembled, and the project structure summary is injected
     * into the system prompt.</p>
     */
    public static class Code {
        /** Code understanding tools master switch. Default false. */
        private boolean enabled = false;

        /** Host project root directory (absolute path). When empty, tools are not assembled. */
        private String projectRoot = "";

        /** Allowed file extensions whitelist (lowercase, with leading dot). */
        private List<String> allowedExtensions = new ArrayList<String>(java.util.Arrays.asList(
                ".java", ".xml", ".yml", ".yaml", ".properties",
                ".sql", ".md", ".txt", ".json", ".csv"));

        /** Max lines returned by a single code_read call. */
        private int maxLines = 500;

        /** Max file size in bytes (rejects larger files to prevent OOM). */
        private long maxFileBytes = 512L * 1024; // 512 KB

        /** Default scan depth for project_structure tool. */
        private int structureDepth = 3;

        /** Whether to inject project structure summary into system prompt. */
        private boolean contextInjection = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getProjectRoot() {
            return projectRoot;
        }

        public void setProjectRoot(String projectRoot) {
            this.projectRoot = projectRoot;
        }

        public List<String> getAllowedExtensions() {
            return allowedExtensions;
        }

        public void setAllowedExtensions(List<String> allowedExtensions) {
            this.allowedExtensions = allowedExtensions;
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

        public int getStructureDepth() {
            return structureDepth;
        }

        public void setStructureDepth(int structureDepth) {
            this.structureDepth = structureDepth;
        }

        public boolean isContextInjection() {
            return contextInjection;
        }

        public void setContextInjection(boolean contextInjection) {
            this.contextInjection = contextInjection;
        }
    }

    /**
     * Prometheus metrics query tool configuration (v0.4).
     *
     * <p>When {@code enabled=true} and {@code base-url} is non-empty, the
     * {@code metrics_query} tool is assembled.</p>
     */
    public static class Metrics {
        /** Master switch. Default false. */
        private boolean enabled = false;

        /** Prometheus base URL, e.g. {@code http://prometheus:9090}. */
        private String baseUrl = "";

        /** HTTP header name for authentication (e.g. "Authorization"). */
        private String authHeader = "";

        /** HTTP header value for authentication (e.g. "Bearer xxx"). */
        private String authHeaderValue = "";

        /** HTTP call timeout in seconds (connect + read share this budget). */
        private int timeoutSeconds = 15;

        /** Max data points returned per series; user-supplied values are clamped to this. */
        private int maxPoints = 200;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
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

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public int getMaxPoints() {
            return maxPoints;
        }

        public void setMaxPoints(int maxPoints) {
            this.maxPoints = maxPoints;
        }
    }

    /**
     * Loki log search tool configuration (v0.4).
     *
     * <p>When {@code enabled=true} and {@code base-url} is non-empty, the
     * {@code log_search} tool is assembled.</p>
     */
    public static class LogSearch {
        /** Master switch. Default false. */
        private boolean enabled = false;

        /** Loki base URL, e.g. {@code http://loki:3100}. */
        private String baseUrl = "";

        /** HTTP header name for authentication. */
        private String authHeader = "";

        /** HTTP header value for authentication. */
        private String authHeaderValue = "";

        /** HTTP call timeout in seconds. */
        private int timeoutSeconds = 15;

        /** Max log lines returned by a single call; user-supplied values are clamped to this. */
        private int maxLines = 500;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
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

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public int getMaxLines() {
            return maxLines;
        }

        public void setMaxLines(int maxLines) {
            this.maxLines = maxLines;
        }
    }

    /**
     * Jaeger distributed trace search tool configuration (v0.4).
     *
     * <p>When {@code enabled=true} and {@code base-url} is non-empty, the
     * {@code trace_search} tool is assembled.</p>
     */
    public static class Trace {
        /** Master switch. Default false. */
        private boolean enabled = false;

        /** Jaeger base URL, e.g. {@code http://jaeger:16686}. */
        private String baseUrl = "";

        /** HTTP header name for authentication. */
        private String authHeader = "";

        /** HTTP header value for authentication. */
        private String authHeaderValue = "";

        /** HTTP call timeout in seconds. */
        private int timeoutSeconds = 15;

        /** Max traces returned by a single search; user-supplied values are clamped to this. */
        private int maxTraces = 20;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
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

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public int getMaxTraces() {
            return maxTraces;
        }

        public void setMaxTraces(int maxTraces) {
            this.maxTraces = maxTraces;
        }
    }

    /**
     * Configuration read tool configuration (v0.4).
     *
     * <p>When {@code enabled=true}, the {@code config_read} tool is assembled.
     * Local mode reads the Spring {@code Environment} (no network); Nacos mode
     * reads from {@code nacos-base-url} and is validated at runtime.</p>
     */
    public static class ConfigRead {
        /** Master switch. Default false. */
        private boolean enabled = false;

        /** Nacos base URL for remote config reads. */
        private String nacosBaseUrl = "";

        /** Default Nacos namespace ID. */
        private String nacosNamespace = "";

        /** Nacos authentication token. */
        private String nacosAuthToken = "";

        /** Max local properties returned by a single call. */
        private int maxKeys = 100;

        /**
         * Property key patterns (lowercase substring match) whose values are
         * masked to {@code ****} in local-mode output.
         */
        private List<String> sensitiveKeyPatterns = new ArrayList<String>(java.util.Arrays.asList(
                "password", "secret", "token", "credential", "key"));

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getNacosBaseUrl() {
            return nacosBaseUrl;
        }

        public void setNacosBaseUrl(String nacosBaseUrl) {
            this.nacosBaseUrl = nacosBaseUrl;
        }

        public String getNacosNamespace() {
            return nacosNamespace;
        }

        public void setNacosNamespace(String nacosNamespace) {
            this.nacosNamespace = nacosNamespace;
        }

        public String getNacosAuthToken() {
            return nacosAuthToken;
        }

        public void setNacosAuthToken(String nacosAuthToken) {
            this.nacosAuthToken = nacosAuthToken;
        }

        public int getMaxKeys() {
            return maxKeys;
        }

        public void setMaxKeys(int maxKeys) {
            this.maxKeys = maxKeys;
        }

        public List<String> getSensitiveKeyPatterns() {
            return sensitiveKeyPatterns;
        }

        public void setSensitiveKeyPatterns(List<String> sensitiveKeyPatterns) {
            this.sensitiveKeyPatterns = sensitiveKeyPatterns;
        }
    }

    /**
     * Active monitoring patrol configuration (v0.5).
     *
     * <p>When {@code enabled=true}, a {@link cn.watsontech.snapagent.core.patrol.PatrolScheduler}
     * is assembled so that skills can be run on cron schedules to proactively
     * detect anomalies.</p>
     */
    public static class Patrol {
        private boolean enabled = false;
        private int schedulerPoolSize = 2;
        private int reportBufferSize = 500;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getSchedulerPoolSize() { return schedulerPoolSize; }
        public void setSchedulerPoolSize(int schedulerPoolSize) { this.schedulerPoolSize = schedulerPoolSize; }
        public int getReportBufferSize() { return reportBufferSize; }
        public void setReportBufferSize(int reportBufferSize) { this.reportBufferSize = reportBufferSize; }
    }

    /**
     * Alert convergence configuration (v0.5).
     *
     * <p>When {@code enabled=true}, an {@link cn.watsontech.snapagent.core.patrol.AlertConverger}
     * is assembled to deduplicate repeated anomaly events into converged alert records.</p>
     */
    public static class Alert {
        private boolean enabled = false;
        private int bufferSize = 1000;
        private int autoResolveMinutes = 30;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getBufferSize() { return bufferSize; }
        public void setBufferSize(int bufferSize) { this.bufferSize = bufferSize; }
        public int getAutoResolveMinutes() { return autoResolveMinutes; }
        public void setAutoResolveMinutes(int autoResolveMinutes) { this.autoResolveMinutes = autoResolveMinutes; }
    }

    /**
     * Embedded business knowledge base configuration (v0.7).
     *
     * <p>When {@code enabled=true}, a {@link cn.watsontech.snapagent.core.knowledge.KnowledgeBase}
     * is assembled from the configured {@code sources} (Markdown directories by default),
     * and a {@link cn.watsontech.snapagent.boot2x.knowledge.KnowledgeInjector} is registered
     * as a {@link cn.watsontech.snapagent.core.agent.SystemPromptExtender} so that relevant
     * business knowledge is injected into the system prompt at runtime.</p>
     */
    public static class Knowledge {
        /** Master switch. Default false — zero knowledge beans when disabled. */
        private boolean enabled = false;

        /** Knowledge source configurations. Each entry defines a directory to load .md files from. */
        private List<KnowledgeSourceConfig> sources = new ArrayList<KnowledgeSourceConfig>();

        /** Maximum number of fragments injected per query. */
        private int maxFragments = 3;

        /** Minimum relevance score [0.0, 1.0] for a fragment to be injected. */
        private double minScore = 0.1;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public List<KnowledgeSourceConfig> getSources() { return sources; }
        public void setSources(List<KnowledgeSourceConfig> sources) { this.sources = sources; }
        public int getMaxFragments() { return maxFragments; }
        public void setMaxFragments(int maxFragments) { this.maxFragments = maxFragments; }
        public double getMinScore() { return minScore; }
        public void setMinScore(double minScore) { this.minScore = minScore; }
    }

    /**
     * Individual knowledge source configuration (v0.7).
     */
    public static class KnowledgeSourceConfig {
        /** Source type. Currently only "markdown" is supported. */
        private String type = "markdown";

        /** Directory path: classpath: prefix for classpath resources, or filesystem path. */
        private String dir = "";

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getDir() { return dir; }
        public void setDir(String dir) { this.dir = dir; }
    }

    /**
     * Code knowledge graph configuration (v0.8).
     *
     * <p>When {@code enabled=true} and {@code code.enabled=true} (shares the v0.3
     * {@link cn.watsontech.snapagent.boot2x.tool.CodePathGuard}), a code graph is
     * built from the project's Java source files and a {@code CodeGraphToolProvider}
     * is assembled so the LLM can query call chains, impact analysis, etc.</p>
     */
    public static class CodeGraph {
        /** Master switch. Default false. */
        private boolean enabled = false;

        /** Package prefixes to scan (empty = scan all .java files under project root). */
        private List<String> scanPackages = new ArrayList<String>();

        /** Maximum depth for call chain queries (forward and reverse). */
        private int maxDepth = 5;

        /** Maximum depth for impact analysis queries. */
        private int maxImpactDepth = 3;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public List<String> getScanPackages() { return scanPackages; }
        public void setScanPackages(List<String> scanPackages) { this.scanPackages = scanPackages; }
        public int getMaxDepth() { return maxDepth; }
        public void setMaxDepth(int maxDepth) { this.maxDepth = maxDepth; }
        public int getMaxImpactDepth() { return maxImpactDepth; }
        public void setMaxImpactDepth(int maxImpactDepth) { this.maxImpactDepth = maxImpactDepth; }
    }

    /**
     * Issue closure configuration (v0.9).
     *
     * <p>When {@code enabled=true}, issue closure infrastructure (FileIssueStore,
     * NoopIssueTracker, KnowledgeSedimentationExtractor) is assembled, enabling
     * the "diagnose -> solution -> fix -> verify -> sediment" lifecycle.</p>
     */
    public static class IssueClosure {
        /** Master switch. Default false — zero issue beans when disabled. */
        private boolean enabled = false;

        /** System user ID used when executing solution-suggest/verify-fix skills. */
        private String systemUserId = "system";

        /** Storage directory for issue JSON files. Empty = default to {upload-skills-dir}/issues/. */
        private String storageDir = "";

        /** IssueTracker type identifier (noop/jira/github). */
        private String trackerType = "noop";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getSystemUserId() { return systemUserId; }
        public void setSystemUserId(String systemUserId) { this.systemUserId = systemUserId; }
        public String getStorageDir() { return storageDir; }
        public void setStorageDir(String storageDir) { this.storageDir = storageDir; }
        public String getTrackerType() { return trackerType; }
        public void setTrackerType(String trackerType) { this.trackerType = trackerType; }
    }

    /**
     * Cost accounting configuration (v1.0).
     *
     * <p>When {@code enabled=true}, cost tracking infrastructure (FileCostStore,
     * BudgetEnforcer, DefaultCostTracker, CostSummaryService) is assembled, and
     * the LlmClient is wrapped in a CostTrackingLlmClient so every LLM call
     * records token usage and cost.</p>
     */
    public static class Cost {
        /** Master switch. Default false — zero cost beans when disabled. */
        private boolean enabled = false;

        /** Pricing configuration. */
        private Pricing pricing = new Pricing();

        /** Budget limits. */
        private Budgets budgets = new Budgets();

        /** Storage directory for cost records. Empty = default to {upload-skills-dir}/cost/. */
        private String storageDir = "";

        /** Budget utilization ratio at which to emit a warning (0.0-1.0). */
        private double warnThreshold = 0.8;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public Pricing getPricing() { return pricing; }
        public void setPricing(Pricing pricing) { this.pricing = pricing; }
        public Budgets getBudgets() { return budgets; }
        public void setBudgets(Budgets budgets) { this.budgets = budgets; }
        public String getStorageDir() { return storageDir; }
        public void setStorageDir(String storageDir) { this.storageDir = storageDir; }
        public double getWarnThreshold() { return warnThreshold; }
        public void setWarnThreshold(double warnThreshold) { this.warnThreshold = warnThreshold; }

        /** Per-million-token pricing. */
        public static class Pricing {
            /** Price per 1M input tokens. */
            private BigDecimal input = new BigDecimal("3.00");
            /** Price per 1M output tokens. */
            private BigDecimal output = new BigDecimal("15.00");
            /** Price per 1M cache-read tokens. */
            private BigDecimal cacheRead = new BigDecimal("0.30");
            /** Currency code (e.g. CNY, USD). */
            private String currency = "CNY";

            public BigDecimal getInput() { return input; }
            public void setInput(BigDecimal input) { this.input = input; }
            public BigDecimal getOutput() { return output; }
            public void setOutput(BigDecimal output) { this.output = output; }
            public BigDecimal getCacheRead() { return cacheRead; }
            public void setCacheRead(BigDecimal cacheRead) { this.cacheRead = cacheRead; }
            public String getCurrency() { return currency; }
            public void setCurrency(String currency) { this.currency = currency; }
        }

        /** Daily budget limits (null = no limit for that dimension). */
        public static class Budgets {
            /** Per-user daily cost limit. Null = no limit. */
            private BigDecimal perUserDaily;
            /** Per-skill daily cost limit. Null = no limit. */
            private BigDecimal perSkillDaily;
            /** Global daily cost limit. Null = no limit. */
            private BigDecimal globalDaily;

            public BigDecimal getPerUserDaily() { return perUserDaily; }
            public void setPerUserDaily(BigDecimal perUserDaily) { this.perUserDaily = perUserDaily; }
            public BigDecimal getPerSkillDaily() { return perSkillDaily; }
            public void setPerSkillDaily(BigDecimal perSkillDaily) { this.perSkillDaily = perSkillDaily; }
            public BigDecimal getGlobalDaily() { return globalDaily; }
            public void setGlobalDaily(BigDecimal globalDaily) { this.globalDaily = globalDaily; }
        }
    }

    /** Workflow engine configuration (v1.0). */
    public static class Workflows {
        /** Master switch. Default false — zero workflow beans when disabled. */
        private boolean enabled = false;

        /**
         * Directory on the filesystem for workflow {@code .yml} files.
         * Empty = default to {upload-skills-dir}/workflows/.
         */
        private String dir = "";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getDir() { return dir; }
        public void setDir(String dir) { this.dir = dir; }
    }

    /**
     * Skill subsystem configuration.
     *
     * <p>Controls the {@link cn.watsontech.snapagent.boot2x.skill.SkillHotReloader}
     * which watches the upload-skills directory for {@code .md} file changes and
     * triggers {@link cn.watsontech.snapagent.core.skill.SkillRegistry#refresh()}
     * automatically.</p>
     */
    public static class Skill {
        /**
         * When true (default), the upload-skills directory is watched for file
         * changes and the skill registry is auto-refreshed. Set
         * {@code snap-agent.skill.hot-reload=false} to disable.
         */
        private boolean hotReload = true;

        public boolean isHotReload() {
            return hotReload;
        }

        public void setHotReload(boolean hotReload) {
            this.hotReload = hotReload;
        }
    }

    /**
     * Anchor Q&amp;A feature configuration (v0.4).
     *
     * <p>When {@code enabled=true} (default), the host page anchor script
     * scans DOM regions marked with {@code data-snap-anchor} and injects
     * anchor icons for in-context LLM Q&amp;A.</p>
     */
    public static class Anchor {
        /** Master switch for the anchor feature. Default true. */
        private boolean enabled = true;

        /** Paths where anchor scanning is disabled (ant-style, e.g. {@code /payment/**}). */
        private List<String> disabledPaths = new ArrayList<>();

        /** Max chars of page-section content sent to LLM per request. */
        private int maxContextChars = 8000;

        /** Pre-summarize + pre-classify on anchor click to reduce first-token latency. */
        private boolean preprocessEnabled = true;

        /** Abort preprocess if user doesn't submit within this many milliseconds. */
        private long preprocessTimeoutMs = 5000;

        /** Skip summarizer for content shorter than this many chars. */
        private int summaryThresholdChars = 4000;

        /** TTL (seconds) for cached summaries in the Caffeine LRU cache. */
        private int summaryCacheTtlSeconds = 600;

        /** LLM model override for the classifier (empty = use default model). */
        private String classifierModel = "";

        /** Confidence below this falls back to general LLM. */
        private double classifierConfidenceThreshold = 0.5;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /**
         * Returns the disabled-paths list. Never null — returns an empty
         * list when the field is null to avoid NPEs in path-matching logic.
         */
        public List<String> getDisabledPaths() {
            return disabledPaths == null ? new ArrayList<>() : disabledPaths;
        }

        public void setDisabledPaths(List<String> disabledPaths) {
            this.disabledPaths = disabledPaths;
        }

        public int getMaxContextChars() {
            return maxContextChars;
        }

        public void setMaxContextChars(int maxContextChars) {
            this.maxContextChars = maxContextChars;
        }

        public boolean isPreprocessEnabled() {
            return preprocessEnabled;
        }

        public void setPreprocessEnabled(boolean preprocessEnabled) {
            this.preprocessEnabled = preprocessEnabled;
        }

        public long getPreprocessTimeoutMs() {
            return preprocessTimeoutMs;
        }

        public void setPreprocessTimeoutMs(long preprocessTimeoutMs) {
            this.preprocessTimeoutMs = preprocessTimeoutMs;
        }

        public int getSummaryThresholdChars() {
            return summaryThresholdChars;
        }

        public void setSummaryThresholdChars(int summaryThresholdChars) {
            this.summaryThresholdChars = summaryThresholdChars;
        }

        public int getSummaryCacheTtlSeconds() {
            return summaryCacheTtlSeconds;
        }

        public void setSummaryCacheTtlSeconds(int summaryCacheTtlSeconds) {
            this.summaryCacheTtlSeconds = summaryCacheTtlSeconds;
        }

        public String getClassifierModel() {
            return classifierModel;
        }

        public void setClassifierModel(String classifierModel) {
            this.classifierModel = classifierModel;
        }

        public double getClassifierConfidenceThreshold() {
            return classifierConfidenceThreshold;
        }

        public void setClassifierConfidenceThreshold(double classifierConfidenceThreshold) {
            this.classifierConfidenceThreshold = classifierConfidenceThreshold;
        }

        /**
         * Checks if the given request path matches any disabled-path pattern.
         *
         * <p>Pattern matching rules:</p>
         * <ul>
         *   <li>Pattern ending with {@code /**}: matches if path equals or
         *       starts with the prefix (e.g. {@code /payment/**} matches
         *       {@code /payment} and {@code /payment/checkout})</li>
         *   <li>Exact pattern: matches if path equals the pattern or starts
         *       with {@code pattern + "/"} (prefix match for sub-paths)</li>
         * </ul>
         *
         * @param path the request path (e.g. {@code /payment/checkout})
         * @return true if the path should not have anchor scanning
         */
        public boolean isPathDisabled(String path) {
            if (path == null || disabledPaths == null || disabledPaths.isEmpty()) {
                return false;
            }
            for (String pattern : disabledPaths) {
                if (pattern == null || pattern.isEmpty()) {
                    continue;
                }
                if (pattern.endsWith("/**")) {
                    String prefix = pattern.substring(0, pattern.length() - 3);
                    if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                        return true;
                    }
                } else {
                    if (path.equals(pattern) || path.startsWith(pattern + "/")) {
                        return true;
                    }
                }
            }
            return false;
        }
    }
}
