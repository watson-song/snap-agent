# SnapAgent v__VERSION__ — 集成指南（AI 可执行）

> **本文件供 Claude Code / AI 编程助手阅读并逐步执行。**
>
> 目标：将 SnapAgent 嵌入一个 Spring Boot 2.x 宿主项目，使其获得内嵌只读诊断 Agent 能力。CICD 无外网，jar 从项目内 `lib/` 目录解析。

---

## 前置条件检查

执行集成前，先确认宿主项目满足以下条件：

1. **Spring Boot 2.x 项目** — 在宿主 `pom.xml` 中确认 `<parent>` 是 `spring-boot-starter-parent` 且版本为 2.5.x ~ 2.7.x。
2. **Java 8+** — `pom.xml` 中 `maven.compiler.source` ≥ 1.8。
3. **有只读 DataSource**（如需 DB 查询）— 项目中已配置一个只读 DataSource Bean，或愿意新建。
4. **有 RedisTemplate Bean**（如需 Redis 查询）— 项目中已配置 Redis。
5. **CICD 无外网** — 所有依赖必须从项目内 `lib/` 或内部 Nexus 镜像解析，不能从 Maven Central 下载。

如果条件 3 或 4 不满足，可暂时跳过对应工具，Agent 仍可运行（只是 DB/Redis 查询技能不可用）。

---

## 集成步骤

### 步骤 1：安装包内容确认

解压安装包后，确认以下文件存在：

```
snap-agent-sf-install-v__VERSION__/
├── install-sf.sh                       # 一键安装脚本
├── lib/                                # Maven 本地文件仓库
│   └── com/watsontech/snapagent/
│       ├── snap-agent-core/__VERSION__/
│       │   ├── snap-agent-core-__VERSION__.jar
│       │   └── snap-agent-core-__VERSION__.pom
│       └── snap-agent-spring-boot-2x-starter/__VERSION__/
│           ├── snap-agent-spring-boot-2x-starter-__VERSION__.jar
│           └── snap-agent-spring-boot-2x-starter-__VERSION__.pom
├── pom-snippet.xml                     # 要粘贴到宿主 pom.xml 的片段
├── application-sf.yml                  # SF 默认配置
├── README.md                           # 本文件（集成指南 + 验证 + 排查）
```

### 步骤 2：复制 lib/ 目录到宿主项目根目录

将安装包内的 `lib/` 目录整体复制到宿主项目根目录（与 `pom.xml` 同级）：

```
宿主项目/
├── pom.xml
└── lib/                                    ← 新增
    └── com/watsontech/snapagent/
        ├── snap-agent-core/__VERSION__/
        │   ├── snap-agent-core-__VERSION__.jar
        │   └── snap-agent-core-__VERSION__.pom
        └── snap-agent-spring-boot-2x-starter/__VERSION__/
            ├── snap-agent-spring-boot-2x-starter-__VERSION__.jar
            └── snap-agent-spring-boot-2x-starter-__VERSION__.pom
```

> `lib/` 是一个标准 Maven 文件仓库。Maven 会从这里解析 SnapAgent 的 jar 和 pom，不访问外部网络。

### 步骤 3：在宿主 pom.xml 中添加本地仓库和依赖

打开宿主项目的 `pom.xml`，在 `<repositories>` 中添加本地文件仓库，在 `<dependencies>` 中添加 SnapAgent 依赖。参考 `pom-snippet.xml`：

```xml
<repositories>
    <repository>
        <id>snap-agent-local</id>
        <!-- ⚠ 多模块项目必须用 ${maven.multiModuleProjectDirectory}，不能用 ${project.basedir} -->
        <!-- ${project.basedir} 在子模块构建时解析为子模块目录，Maven 找不到根目录的 lib/ -->
        <!-- ${maven.multiModuleProjectDirectory}（Maven 3.3.1+）始终指向项目根目录 -->
        <url>file://${maven.multiModuleProjectDirectory}/lib</url>
        <releases>
            <enabled>true</enabled>
            <checksumPolicy>ignore</checksumPolicy>
        </releases>
        <snapshots>
            <enabled>false</enabled>
        </snapshots>
    </repository>
</repositories>

<dependencies>
    <!-- SnapAgent Starter（自动引入 snap-agent-core） -->
    <dependency>
        <groupId>com.watsontech.snapagent</groupId>
        <artifactId>snap-agent-spring-boot-2x-starter</artifactId>
        <version>__VERSION__</version>
    </dependency>

    <!-- OkHttp（LLM 流式调用必需，Spring Boot 不自带） -->
    <!-- 如果宿主项目已有 okhttp 可跳过 -->
    <dependency>
        <groupId>com.squareup.okhttp3</groupId>
        <artifactId>okhttp</artifactId>
    </dependency>
</dependencies>
```

> 注意：优先使用 `file://` 文件仓库方式，能正确解析传递依赖。但如果 CI/CD 的 `settings.xml` 配置了 `<mirror>`（如 `<mirrorOf>*</mirror>` 重定向到 Artifactory），`file://` 仓库会被全局拦截，需改用 **system scope** 方式（见 `pom-snippet.xml` 方案二）。
>
> **多模块项目（重要）**：`<repository>` 的 URL 必须用 `${maven.multiModuleProjectDirectory}` 而非 `${project.basedir}`。后者在子模块构建时解析为子模块目录而非项目根目录，Maven 找不到 `lib/` 会静默回退到远程仓库，导致 CI/CD 报 `Could not find artifact`。`${maven.multiModuleProjectDirectory}` 是 Maven 3.3.1+ 内置变量，始终指向多模块项目的根目录。

### 步骤 3b：配置宿主项目自有 Skill 的 classpath 打包（如有）

> **AI 执行指引**：先检查宿主项目是否有 `docs/skills/` 目录且包含 `.md` 文件（含子目录中的 `SKILL.md`）。如果有，执行以下配置；如果没有，跳过此步。

SnapAgent 内置 Skill 打包在 starter JAR 的 `classpath:/docs/skills/` 中。如果宿主项目也有自己的 Skill 文件放在 `docs/skills/` 下，需要将该目录加入 Maven 资源打包，否则不会出现在 classpath 中。

**检查方法**：
```bash
# 用 find 而非 ls，因为 Skill 文件常在子目录中（如 docs/skills/my-skill/SKILL.md）
find docs/skills -name "*.md" 2>/dev/null | head -5
# 有输出 → 有自有 Skill，需配置；无输出 → 跳过此步
```

**有则配置**：在宿主 `pom.xml` 的 `<build>` → `<resources>` 中添加（与现有 `<resources>` 合并，不要覆盖）：
```xml
<build>
    <resources>
        <!-- 保留原有 resource 配置 -->
        <resource>
            <directory>src/main/resources</directory>
        </resource>
        <!-- 新增：把 docs/skills/ 下的 .md 打入 classpath:/docs/skills/ -->
        <resource>
            <!-- 单模块项目：用相对路径 docs -->
            <!-- 多模块项目：docs/ 在项目根目录而非子模块目录时，用 ${maven.multiModuleProjectDirectory}/docs -->
            <directory>docs</directory>
            <targetPath>docs</targetPath>
            <includes>
                <include>skills/**/*.md</include>
            </includes>
        </resource>
    </resources>
</build>
```

> 打包后宿主 Skill 与 SnapAgent 内置 Skill 合并在同一个 `classpath:/docs/skills/` 路径下。同名时宿主的覆盖 starter 的（custom 覆盖 builtin 机制）。
> **多模块项目注意**：如果 `pom.xml` 在子模块目录（如 `scpdrp-starter/pom.xml`）而 `docs/` 在项目根目录，`<directory>docs</directory>` 会解析为子模块下的 `docs`（不存在）。此时需改为 `<directory>${maven.multiModuleProjectDirectory}/docs</directory>`（Maven 3.3.1+ 内置变量，始终指向项目根目录）。
> 如果宿主项目没有自己的 Skill 文件，跳过此步即可——starter JAR 内置的 4 个 Skill 已经够用。

### 步骤 4：在 application.yml 中添加 SnapAgent 配置

**先向用户确认工号**：在写入配置前，向用户询问：

> 集成 SnapAgent 需要 cc-switch 代理的 Auth Token（即你的工号）。请提供你的工号，我会填入 `auth-token` 配置中。

拿到工号后，将 `application-sf.yml` 的内容合并到宿主项目的 `application.yml`（或 `application.properties`）。关键配置：

```yaml
snap-agent:
  enabled: true
  base-path: /snap-agent
  upload-skills-dir: /app/deploy/skills
  llm:
    api-type: anthropic            # ← anthropic（cc-switch 网关）或 openai（LLM Model Hub 等 OpenAI 兼容接口）
    base-url: https://claudecode.sf-express.com/ccr
    auth-token: <使用者工号>        # ← 替换为用户提供的工号
    model: aliyun/glm-5.2
    allowed-models:
      - aliyun/glm-5.2
    streaming: true
    proxy-url: ""                  # ← K8s Pod 无法直连外网时填 http://proxy-host:port
  security:
    required-permission: snap-agent:access  # ← 用户需拥有此权限才能访问 SnapAgent
  jdbc:
    enabled: true
    datasource-bean-name: snapAgentReadOnlyDataSource
  redis:
    enabled: true
    redis-template-bean-name: redisTemplate
  logs:
    enabled: true
    allowed-paths:
      - <宿主项目的日志目录>    # ← 替换为实际路径，如 /app/logs
```

> - `api-type` 决定 LLM 客户端实现：`anthropic`（默认，走 cc-switch 网关的 Anthropic Messages API）或 `openai`（走 LLM Model Hub 等 OpenAI 兼容的 Chat Completions API）。如果 `base-url` 指向 `llm-model-hub-apis.sf-express.com` 等 OpenAI 兼容端点，须设为 `openai`。
> - `base-url` 是 LLM API 地址。cc-switch 代理网关（`api-type: anthropic`）默认指向 `https://claudecode.sf-express.com/ccr`；LLM Model Hub（`api-type: openai`）指向 `https://llm-model-hub-apis.sf-express.com`。
> - `auth-token` 是使用者的工号，作为 Bearer token 发送。**不要硬编码任何人的工号**，必须向用户确认后填入。
> - `upload-skills-dir: /app/deploy/skills` 是 SF 服务器上的 Skill 上传目录，重启后持久化。需确保该目录存在且应用账号有读写权限。
> - `allowed-models` 是服务端强制白名单，前端无法绕过。
> - `proxy-url` 是 HTTP 代理地址。当 K8s Pod 无法直连外网（即 `base-url` 指向的 cc-switch 网关不可达）时，配置一个可达的 HTTP 代理（如 `http://10.x.x.x:3128`），LLM 请求将通过代理转发。留空则直连。
> - `logs.allowed-paths` 是日志分析 Skill 可读的目录白名单。**必须替换为宿主项目实际的日志目录**（通常是 `logging.file.name` 的父目录或 `logging.path`）。未配置则 `log-analysis` skill 不可用。
> - `security.required-permission` 是访问 SnapAgent 的权限标识，默认 `snap-agent:access`。用户必须在宿主权限系统中拥有此权限才能访问 SnapAgent（Spring Security 的 `GrantedAuthority` 或 Shiro 的 permission）。如需放开给所有登录用户，设为空字符串。

### 步骤 5：创建上传 Skill 目录

在服务器上创建 `/app/deploy/skills` 目录并赋权：

```bash
mkdir -p /app/deploy/skills
chmod 750 /app/deploy/skills
chown <应用账号>:<应用组> /app/deploy/skills
```

> 如果应用以 root 运行可跳过 chown。这个目录用于运行时通过 API 上传或运维直接放置 Skill 文件。

### 步骤 6：提供只读 DataSource Bean（如需 DB 查询）

在宿主项目的 `@Configuration` 类中，新增一个只读 DataSource Bean。这个 DataSource 应连接一个**只读权限**的数据库账号（只有 SELECT 权限），作为安全兜底：

```java
import org.springframework.boot.jdbc.DataSourceBuilder;
import javax.sql.DataSource;

@Bean
public DataSource snapAgentReadOnlyDataSource() {
    return DataSourceBuilder.create()
            .url("jdbc:mysql://your-db-host:3306/your_db?useSSL=false&serverTimezone=Asia/Shanghai")
            .username("readonly_user")       // 只读账号
            .password("readonly_password")
            .build();
}
```

> 也可以复用主 DataSource，但不推荐——主 DataSource 的账号可能有写权限，一旦 LLM 被 prompt 注入生成写 SQL，只读账号是最后一道防线。SqlGuard 在语法层已拦截写操作，只读账号是语义层兜底。

### 步骤 7：放行 SnapAgent 端点（Spring Security 白名单）

如果宿主项目使用了 Spring Security，必须在安全配置中放行 SnapAgent 的**公开端点**。其余 API 端点要求用户登录（SnapAgent 内部完成鉴权，但请求需能到达它）：

```java
import org.springframework.security.config.annotation.web.builders.HttpSecurity;

@Override
protected void configure(HttpSecurity http) throws Exception {
    http.authorizeRequests()
        // 公开：前端鉴权配置（返回 token header/cookie/localStorage key 名，无敏感信息）
        .antMatchers("/snap-agent/auth-config").permitAll()
        // 公开：静态资源（SPA 页面壳）
        .antMatchers("/snap-agent/*.html", "/snap-agent/*.js",
                     "/snap-agent/*.css").permitAll()
        // 公开：SSE 流端点（EventSource 自动发送 Cookie；Controller 校验任务归属）
        .antMatchers("/snap-agent/runs/*/stream").permitAll()
        // 公开：跨 Pod 内部路由（使用共享密钥）
        .antMatchers("/snap-agent-internal/**").permitAll()
        // 其他 SnapAgent API 端点均要求登录（含 /user-info）
        .antMatchers("/snap-agent/**").authenticated()
        // 其他端点按宿主原有策略
        .anyRequest().authenticated();
}
```

> **重要**：`/snap-agent/user-info` **不要**加入 JWT filter 白名单/跳过列表。`/user-info` 需要经过宿主认证链，`SecurityContext` 被填充后 SnapAgent 才能读取用户身份。如果 JWT filter 跳过该 URL，`SecurityContext` 不会被填充，SnapAgent 会看到 `anonymousUser`。前端已经处理 401 响应——未登录时显示"登录失效"提示。

> **安全模型说明**：
> - `/auth-config` 是公开端点，返回前端鉴权配置（`authHeader`、`authCookie`、`authLocalStorageKey`），告诉前端从哪里读 token、以什么 header 名发送。无敏感信息。
> - `/user-info` **需要认证**，返回已登录用户的 `{ authenticated, authorized, userId, username, message }`。前端页面加载时先调 `/auth-config` 获取配置，再带 token 调 `/user-info` 判断用户状态。
> - 所有其他 API 端点均要求用户已登录。SnapAgent Controller 内部通过 `SecurityGateway` 检查用户身份和 `required-permission` 权限（默认 `snap-agent:access`）。
> - `/runs/*/stream`（SSE）虽然 permitAll，但 Controller 会校验任务归属（仅任务创建者可订阅流）。EventSource 不支持自定义 Header，但会自动发送 Cookie，因此同源请求的 Session/Cookie 鉴权可以正常工作。
> - **权限配置**：SnapAgent 默认要求用户拥有 `snap-agent:access` 权限标识。宿主需在权限系统中为用户分配此权限：
>   - **Spring Security**：用户的 `GrantedAuthority` 列表中需包含 `snap-agent:access`（非 `ROLE_` 前缀，是精确匹配）
>   - **Shiro**：用户需被分配 `snap-agent:access` 权限（通配符匹配）
>   - 如需放开给所有登录用户，设 `snap-agent.security.required-permission: ""`（空字符串）
>
> **端点清单**：
>
> | 端点 | 方法 | 公开/认证 | 说明 |
> |------|------|----------|------|
> | `/auth-config` | GET | 公开 | 前端鉴权配置（header/cookie/localStorage key 名） |
> | `/*.html`, `/*.js`, `/*.css` | GET | 公开 | SPA 静态资源 |
> | `/runs/{id}/stream` | GET | 公开 | SSE 流（Controller 校验任务归属） |
> | `/snap-agent-internal/**` | GET | 公开 | 跨 Pod 内部路由（共享密钥） |
> | `/user-info` | GET | 认证 | 当前用户登录/授权状态 |
> | `/skills` | GET | 认证 | Skill 列表 |
> | `/skills/{name}` | DELETE | 认证 | 删除自定义 Skill（builtin 不可删） |
> | `/skills/upload` | POST | 认证 | 上传单个 Skill 文件（.md/.zip） |
> | `/skills/upload-folder` | POST | 认证 | 上传 Skill 目录（多文件） |
> | `/skills/refresh` | POST | 认证 | 刷新 Skill 列表 |
> | `/models` | GET | 认证 | 可用模型列表 |
> | `/tools` | GET | 认证 | 可用工具列表 |
> | `/runs` | POST | 认证 | 启动 Agent 执行 |
> | `/runs/{id}` | GET | 认证 | 查询任务状态 |
> | `/runs/{id}/transcript` | GET | 认证 | 获取任务完整 transcript |
>
> 如果宿主用 Shiro，在 Shiro 链定义中放行上述公开路径，其余 `/snap-agent/**` 要求登录。
> 如果宿主无安全框架，跳过此步骤（但 SnapAgent 将无法识别用户身份）。

### 步骤 8：响应包装过滤器跳过 SSE 流端点

如果宿主项目有**响应包装过滤器**（将所有响应统一包装为 `{ "code": 0, "data": ... }` 信封），必须让它跳过 `/stream` 路径，否则 SSE 流会被缓冲，前端无法实时收到事件：

```java
// 在响应包装过滤器中
String requestUri = request.getRequestURI();
if (requestUri != null && requestUri.contains("/stream")) {
    chain.doFilter(request, response);  // 原样放行，不包装
    return;
}
// ...原有包装逻辑
```

> 仅 `/stream` 路径需豁免。`/skills`、`/runs` 等 REST 端点被包装无影响。
> 如果用 `@ControllerAdvice` / `ResponseBodyAdvice` 做统一包装，同理需排除 `text/event-stream` 返回类型。

### 步骤 9：Nginx 代理 SnapAgent 路径（SF 环境必需）

SF 的 Nginx 默认只代理 `/api` 到后端，`/snap-agent` 路径不会到达应用。必须在 Nginx server 块中添加 `/snap-agent` 和 `/snap-agent-internal` 的 location：

```nginx
server {
    listen       80;
    server_name  your-app.sit.sf-express.com;

    # 原有 API 代理
    location /api {
       proxy_pass http://your-app-backend:1080;
       proxy_read_timeout 600s;
       proxy_connect_timeout 120s;
       proxy_send_timeout 600s;
    }

    # SnapAgent 代理到后端
    location /snap-agent {
       proxy_pass http://your-app-backend:1080;
       proxy_read_timeout 600s;
       proxy_connect_timeout 120s;
       proxy_send_timeout 600s;

       # SSE 流式传输必需：禁用缓冲
       proxy_buffering off;
       proxy_cache off;

       # HTTP/1.1 长连接（SSE 必需）
       proxy_http_version 1.1;
       proxy_set_header Connection "";

       # 透传客户端信息
       proxy_set_header Host $host;
       proxy_set_header X-Real-IP $remote_addr;
       proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }

    # SnapAgent 跨 Pod 内部路由（多实例时需要，单实例可省略）
    location /snap-agent-internal {
       proxy_pass http://your-app-backend:1080;
       proxy_read_timeout 60s;
       proxy_connect_timeout 10s;
    }

    location / {
        root   html;
        index  index.html index.htm;
    }
}
```

| 配置项 | 原因 |
|--------|------|
| `proxy_buffering off` | SSE 流必须禁用缓冲，否则前端收不到实时事件 |
| `proxy_http_version 1.1` | SSE 需要 HTTP/1.1 长连接 |
| `proxy_set_header Connection ""` | 保持长连接不断开 |
| `proxy_read_timeout 600s` | Agent 诊断可能多轮 LLM 调用，耗时较长 |
| `/snap-agent-internal` | 多实例部署时跨 Pod 路由用，单实例可不要 |

> 如果 Nginx 已有通配 `location /` 代理到后端，则 `/snap-agent` 会被自动包含，无需单独配置。仅在 SF 环境（`/api` 独立代理、`/` 指向静态资源）时需要单独添加。
> 验证：配置后访问 `http://your-app.sit.sf-express.com/snap-agent/` 应看到 SnapAgent Web UI。

---

## 验证集成

完成所有步骤后，启动宿主应用并依次验证：

1. **启动日志无报错** — 搜索 `SnapAgent` 或 `snapagent`，确认有 `Loaded N builtin skill(s)` 日志。

2. **访问 auth-config（公开，无需登录）**：
   ```bash
   curl http://localhost:8080/snap-agent/auth-config
   ```
   应返回 `{"authHeader":"","authCookie":"","authLocalStorageKey":""}`（未配置 token 鉴权时均为空字符串）。

3. **访问 user-info（需登录）**：
   ```bash
   # 替换为宿主项目的认证方式（如 Cookie、Basic Auth、Token Header 等）
   curl -u user:password http://localhost:8080/snap-agent/user-info
   ```
   应返回 `{"authenticated":true,"authorized":true,"userId":"...","username":"..."}`。
   未登录时应返回 401。

4. **访问 Skill 列表（需登录）**：
   ```bash
   curl -u user:password http://localhost:8080/snap-agent/skills
   ```
   应返回包含 `health-check`、`database-query`、`redis-query`、`log-analysis` 的 skill 列表。

5. **访问模型列表（需登录）**：
   ```bash
   curl -u user:password http://localhost:8080/snap-agent/models
   ```
   应返回 `default: aliyun/glm-5.2`。

6. **访问工具列表（需登录）**：
   ```bash
   curl -u user:password http://localhost:8080/snap-agent/tools
   ```
   应返回 `mysql_query`（如 JDBC 已配置）、`redis_get`（如 Redis 已配置）和 `log_read`（如 logs 已配置）。

7. **打开 Web UI**：浏览器访问 `http://localhost:8080/snap-agent/`，应看到 Skill 选择页面（顶部显示用户名）。

8. **跑一个 Skill**：选 `health-check`，点 Run，看 SSE 流是否实时输出 thought → tool_call → tool_result → done。

**全部通过 = 集成成功。**

---

## 问题排查与修复

集成过程中可能遇到以下问题。按现象逐项排查：

### 1. `GET /skills` 返回 404

| 可能原因 | 排查方法 | 修复 |
|---------|---------|------|
| `snap-agent.enabled` 未设为 true | 检查 `application.yml` 中 `snap-agent.enabled` | 设为 `true` |
| `base-path` 被宿主 Controller 抢占 | 检查宿主项目是否有 `@RequestMapping("/snap-agent")` | 修改 `base-path` 为其他路径，如 `/diag-agent` |
| Spring Security 拦截了请求 | 检查 Security 配置是否放行 `/snap-agent/**` | 见步骤 7 |
| 宿主项目有全局 `@RestControllerAdvice` 拦截 | 检查是否有切面拦截了所有请求 | 让 Advice 跳过 `/snap-agent/**` |

### 2. `GET /skills` 返回空列表

| 可能原因 | 排查方法 | 修复 |
|---------|---------|------|
| `lib/` 没复制全 | 确认 `lib/com/watsontech/snapagent/` 下有 jar 和 pom | 重新复制 `lib/` 目录 |
| `builtin-skills-dir` 路径错误 | 确认配置有 `builtin-skills-dir: classpath:/docs/skills/` | 添加该配置项 |

### 3. `GET /skills` 中 skill 状态为 `UNAVAILABLE`

| 可能原因 | 排查方法 | 修复 |
|---------|---------|------|
| skill 引用的 tool 未注册（如 JDBC 未配置） | 检查 `snap-agent.jdbc.enabled` 是否为 true | 设为 `true` 并提供 DataSource Bean |
| Redis 未配置 | 检查 `snap-agent.redis.enabled` 是否为 true | 设为 `true` 并提供 RedisTemplate Bean |
| log_read 未配置 | 检查 `snap-agent.logs.enabled` 是否为 true，`allowed-paths` 是否配了实际日志目录 | 设为 `true` 并配置 `allowed-paths` |
| DataSource Bean 名称不匹配 | 确认 `datasource-bean-name` 与实际 Bean 名称一致 | 修改配置或 Bean 名称 |

### 4. `POST /runs` 返回 400 `MODEL_NOT_ALLOWED`

| 可能原因 | 排查方法 | 修复 |
|---------|---------|------|
| 前端传了不在 `allowed-models` 里的 model | 检查 `allowed-models` 是否包含 `aliyun/glm-5.2` | 添加 `aliyun/glm-5.2` 到白名单 |

### 5. `POST /runs` 返回 500 `NoClassDefFoundError: okhttp3/...`

| 可能原因 | 排查方法 | 修复 |
|---------|---------|------|
| okhttp 不在 classpath | 检查 `pom.xml` 是否有 okhttp 依赖 | 添加 okhttp 依赖（见步骤 3） |
| 依赖未从 lib/ 正确解析 | 运行 `mvn dependency:tree | grep snapagent` 确认 jar 被解析 | 确认 `file://` repository 配置正确 |

### 6. `POST /runs` 返回 403

| 可能原因 | 排查方法 | 修复 |
|---------|---------|------|
| 用户已登录但缺少 `required-permission` 权限 | 检查 `snap-agent.security.required-permission` 配置，确认用户在宿主权限系统中拥有该权限 | 为用户分配权限，或留空 `required-permission`（允许所有登录用户） |
| Spring Security 未放行 `/snap-agent/runs/**` | 检查 Security 配置 | 见步骤 7，确保 `/snap-agent/**`（公开端点除外）为 `authenticated()` |
| 宿主有其他安全拦截器（如自定义拦截器） | 检查 `WebMvcConfigurer` 的 `addInterceptors` | 让拦截器跳过 `/snap-agent/**` |

### 7. SSE 流前端长时间无响应

| 可能原因 | 排查方法 | 修复 |
|---------|---------|------|
| 响应包装过滤器没跳过 /stream | 检查宿主项目的 Filter 配置 | 见步骤 8，让过滤器跳过 `/stream` 路径 |
| `@ControllerAdvice` 包装了 SSE 响应 | 检查是否有 `ResponseBodyAdvice` 包装所有返回 | 排除 `text/event-stream` 返回类型 |
| Spring Security 拦截了 `/snap-agent/runs/*/stream` | 检查 Security 放行配置 | 确保放行 `/snap-agent/runs/*/stream` |

### 8. LLM 调用超时

| 可能原因 | 排查方法 | 修复 |
|---------|---------|------|
| cc-switch 代理不可达 | `curl -I https://claudecode.sf-express.com/ccr` 测试连通性 | 联系运维确认代理可用 |
| `timeout-seconds` 太小 | 检查配置 | 调大 `timeout-seconds`（默认 120s） |
| `auth-token` 错误或过期 | 确认工号填写正确 | 重新确认工号并更新配置 |

### 9. 启动报错 `BeanCreationException`

| 可能原因 | 排查方法 | 修复 |
|---------|---------|------|
| DataSource Bean 名称与配置不匹配 | 检查 `datasource-bean-name` 与 `@Bean` 方法名一致 | 修改配置或 Bean 名称 |
| RedisTemplate Bean 不存在 | 确认项目有 Redis 配置 | 添加 Redis 配置或设 `snap-agent.redis.enabled: false` |
| 配置项拼写错误 | 检查 YAML 缩进和 key 名称 | 对照 `application-sf.yml` 修正 |

### 10. CI/CD 构建报 `Could not find artifact com.watsontech.snapagent:...`

| 可能原因 | 排查方法 | 修复 |
|---------|---------|------|
| CI/CD 的 `<mirror>` 拦截了 file:// 仓库 | 日志只有 `Downloading from maven:` 没有 `snap-agent-local:` | 改用 system scope 方式（见 `pom-snippet.xml` 方案二） |
| 多模块项目用了 `${project.basedir}` | 检查 pom.xml 中 repository URL 是否用了 `${project.basedir}/lib` | 改为 `${maven.multiModuleProjectDirectory}/lib`（见步骤 3 注释） |
| `lib/` 目录未提交到 Git | `git ls-files lib/ | head`，输出为空则未提交 | `git add lib/` 并提交 |
| pom.xml 的 repository/dependency 改动未提交 | `git log --oneline -5 -- pom.xml` 检查 | 提交 pom.xml 改动 |

> **最常见原因**：CI/CD 的 `settings.xml` 配置了 `<mirror>`（如 `<mirrorOf>*</mirror>`），将所有仓库请求重定向到 Artifactory，`file://` 本地文件仓库被完全绕过。日志特征：只有 `Downloading from maven: https://artifactory...`，没有 `Downloading from snap-agent-local:`。此时 `file://` 仓库方案不可用，必须改用 **system scope** 方式（见 `pom-snippet.xml` 方案二），需显式声明 starter 和 core 两个依赖，并配置 `<includeSystemScope>true</includeSystemScope>`。

### 11. 项目特有的其他问题

如果遇到上述未覆盖的问题，根据错误信息分析：

- **ClassNotFoundException / NoClassDefFoundError**: 检查 `lib/` 是否完整复制，`pom.xml` 依赖是否正确。用 `mvn dependency:tree` 确认依赖树。
- **Bean 注入失败**: 检查 Bean 名称是否与配置一致，检查是否有包扫描遗漏。
- **端口冲突**: 如果 8080 被占用，确认 SnapAgent 的 `base-path` 不会与其他 Controller 冲突。
- **YAML 合并冲突**: 如果宿主项目已有 `snap-agent` 前缀的配置（少见），注意 YAML 合并规则，确保配置项不丢失。

---

## 升级与回滚

- **升级**：替换 `lib/` 中的 jar 和 pom，更新 `pom.xml` 中的版本号，重启应用。
- **回滚**：恢复旧版 jar/pom 到 `lib/`，更新 `pom.xml` 版本号，重启应用。
- **配置不变**：升级/回滚只需替换 jar，`application.yml` 配置无需改动。

---

## 内置 Skill 说明

v__VERSION__ 内置 4 个 Skill（打包在 JAR 的 classpath:/docs/skills/ 中，只读不可删除）：

| Skill | 工具 | 用途 |
|-------|------|------|
| `health-check` | `mysql_query` | 验证数据库连通性、检查表 |
| `database-query` | `mysql_query` | 自然语言转 SQL 查询业务数据 |
| `redis-query` | `redis_get` | 读取 Redis key 的值和存在性 |
| `log-analysis` | `log_read` | 分析日志文件中的异常、用户操作、时间段事件 |

运行时可通过 `POST /snap-agent/skills/upload` 上传自定义 Skill 到 `/app/deploy/skills`，重启后保留。

---

## 默认配置速查

| 项 | 值 |
|----|----|
| LLM 代理 | `https://claudecode.sf-express.com/ccr`（cc-switch） |
| 模型 | `aliyun/glm-5.2` |
| Auth Token | 使用者工号（Bearer，部署时由 AI 询问后填入） |
| 访问权限 | `snap-agent:access`（用户需拥有此权限标识） |
| Skill 上传目录 | `/app/deploy/skills` |
| 内置 Skill | health-check, database-query, redis-query, log-analysis |
| Web UI | `http://<host>:<port>/snap-agent/` |
| API 基础路径 | `/snap-agent` |

---

## 不要做的事

- **不要在无 mirror 拦截时用 `system` scope** — 优先用 `file://` 文件仓库，能正确解析传递依赖。仅当 CI/CD 的 `<mirror>` 拦截了 `file://` 仓库时才使用 system scope（需显式声明 core + starter 两个依赖，并配置 `<includeSystemScope>true</includeSystemScope>`）。
- **不要给 SnapAgent 的 DataSource 用读写账号** — 只读账号是 prompt 注入的最后一道防线。
- **不要在 `allowed-models` 里加不需要的模型** — 白名单越窄越安全。
- **不要忘记放行 `/snap-agent/runs/*/stream`** — SSE 端点被 Security 拦截会导致前端无法订阅流。
- **不要把 `/app/deploy/skills` 设为 777** — 750 即可，仅应用账号可读写。

---

## 问题升级

如果排查后确认问题属于 **SnapAgent 本身的缺陷**（非宿主项目配置问题），例如：

- SnapAgent jar 中有 bug 导致功能异常
- 文档描述与实际行为不符
- 内置 Skill 无法正常工作（且配置正确）
- LLM 调用流程异常（非网络问题）

请通知 **Watson**（SnapAgent 维护者），并提供以下信息：

1. SnapAgent 版本：`__VERSION__`
2. 宿主项目名称和 Spring Boot 版本
3. 完整的错误日志（含 stack trace）
4. 复现步骤
5. 已尝试的排查操作

Watson 会根据反馈优化集成文档或修复 SnapAgent 代码。

---

## 一键安装脚本（可选）

如果未使用 AI 编程助手，可直接运行安装包内的 `install-sf.sh`：

```bash
# 解压安装包
unzip snap-agent-sf-install-v__VERSION__.zip

# 运行安装脚本，指向你的 Spring Boot 项目
bash snap-agent-sf-install-v__VERSION__/install-sf.sh /path/to/your-project
```

脚本会把 `lib/`、`application-sf.yml`、`pom-snippet.xml` 复制到项目根目录。
之后按本文件的步骤 3 ~ 8 手动完成剩余代码改动。

---

SnapAgent __VERSION__ | Apache 2.0
