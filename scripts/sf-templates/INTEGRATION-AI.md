# SnapAgent v__VERSION__ 集成指南（AI 可执行版）

> 本文档供 Claude Code / AI 编程助手阅读并逐步执行。目标：将 SnapAgent 嵌入一个 Spring Boot 2.x 宿主项目，使其获得内嵌只读诊断 Agent 能力。CICD 无外网，jar 从项目内 `lib/` 目录解析。

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

### 步骤 1：复制 lib/ 目录到宿主项目根目录

将本安装包内的 `lib/` 目录整体复制到宿主项目根目录（与 `pom.xml` 同级）：

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

### 步骤 2：在宿主 pom.xml 中添加本地仓库和依赖

打开宿主项目的 `pom.xml`，在 `<repositories>` 中添加本地文件仓库，在 `<dependencies>` 中添加 SnapAgent 依赖。参考 `pom-snippet.xml`：

```xml
<repositories>
    <repository>
        <id>snap-agent-local</id>
        <url>file://${project.basedir}/lib</url>
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

> 注意：不要使用 `<scope>system</scope>` + `<systemPath>`，那样无法解析传递依赖。文件仓库方式能让 Maven 正确解析 SnapAgent 的完整依赖树。

### 步骤 3：在 application.yml 中添加 SnapAgent 配置

将 `application-sf.yml` 的内容合并到宿主项目的 `application.yml`（或 `application.properties`）。关键配置：

```yaml
snap-agent:
  enabled: true
  base-path: /snap-agent
  upload-skills-dir: /app/deploy/skills
  llm:
    base-url: https://claudecode.sf-express.com/ccr
    auth-token: 01414185
    model: aliyun/glm-5.2
    allowed-models:
      - aliyun/glm-5.2
    streaming: true
  jdbc:
    enabled: true
    datasource-bean-name: snapAgentReadOnlyDataSource
  redis:
    enabled: true
    redis-template-bean-name: redisTemplate
```

> - `base-url` + `auth-token` 是 cc-switch 代理网关地址，LLM 请求通过它路由到 `aliyun/glm-5.2` 模型。
> - `upload-skills-dir: /app/deploy/skills` 是 SF 服务器上的 Skill 上传目录，重启后持久化。需确保该目录存在且应用账号有读写权限。
> - `allowed-models` 是服务端强制白名单，前端无法绕过。

### 步骤 4：创建上传 Skill 目录

在服务器上创建 `/app/deploy/skills` 目录并赋权：

```bash
mkdir -p /app/deploy/skills
chmod 750 /app/deploy/skills
chown <应用账号>:<应用组> /app/deploy/skills
```

> 如果应用以 root 运行可跳过 chown。这个目录用于运行时通过 API 上传或运维直接放置 Skill 文件。

### 步骤 5：提供只读 DataSource Bean（如需 DB 查询）

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

### 步骤 6：放行 SnapAgent 端点（Spring Security 白名单）

如果宿主项目使用了 Spring Security，必须在安全配置中放行 SnapAgent 的所有端点。SnapAgent 内部完成自身鉴权，但需要请求能到达它：

```java
import org.springframework.security.config.annotation.web.builders.HttpSecurity;

@Override
protected void configure(HttpSecurity http) throws Exception {
    http.authorizeRequests()
        // 静态资源
        .antMatchers("/snap-agent/*.html", "/snap-agent/*.js",
                     "/snap-agent/*.css").permitAll()
        // SnapAgent API 全部 permitAll
        .antMatchers("/snap-agent/skills").permitAll()
        .antMatchers("/snap-agent/skills/**").permitAll()
        .antMatchers("/snap-agent/models").permitAll()
        .antMatchers("/snap-agent/tools").permitAll()
        .antMatchers("/snap-agent/runs").permitAll()
        .antMatchers("/snap-agent/runs/*").permitAll()
        .antMatchers("/snap-agent/runs/*/stream").permitAll()
        .antMatchers("/snap-agent/runs/*/transcript").permitAll()
        .antMatchers("/snap-agent-internal/**").permitAll()
        // 其他端点按宿主原有策略
        .anyRequest().authenticated();
}
```

> 如果宿主用 Shiro，在 Shiro 链定义中放行 `/snap-agent/**`。
> 如果宿主无安全框架，跳过此步骤。

### 步骤 7：响应包装过滤器跳过 SSE 流端点

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

### 步骤 8：验证集成

启动宿主应用后，依次验证：

1. **启动日志无报错** — 搜索 `SnapAgent` 或 `snapagent`，确认有 `Loaded N builtin skill(s)` 日志。
2. **访问 Skill 列表**：
   ```bash
   curl http://localhost:8080/snap-agent/skills
   ```
   应返回包含 `health-check`、`database-query`、`redis-query` 的 skill 列表。
3. **访问模型列表**：
   ```bash
   curl http://localhost:8080/snap-agent/models
   ```
   应返回 `default: aliyun/glm-5.2`。
4. **访问工具列表**：
   ```bash
   curl http://localhost:8080/snap-agent/tools
   ```
   应返回 `mysql_query`（如 JDBC 已配置）和 `redis_get`（如 Redis 已配置）。
5. **打开 Web UI**：浏览器访问 `http://localhost:8080/snap-agent/`，应看到 Skill 选择页面。
6. **跑一个 Skill**：选 `health-check`，点 Run，看 SSE 流是否实时输出 thought → tool_call → tool_result → done。

---

## 常见问题排查

| 现象 | 原因 | 解决 |
|------|------|------|
| `GET /skills` 404 | `enabled` 未设 true；或 `base-path` 被宿主 controller 抢占；或 security 没放行 | 检查 application.yml 的 `snap-agent.enabled`；检查 base-path 配置；检查 Security 放行 |
| `GET /skills` 返回空列表 | lib/ 没复制全；或 builtin-skills-dir 路径错 | 确认 lib/ 下有 jar；确认 `builtin-skills-dir: classpath:/docs/skills/` |
| `POST /runs` 400 `MODEL_NOT_ALLOWED` | 前端传了不在 `allowed-models` 里的 model | 检查 `allowed-models` 是否包含 `aliyun/glm-5.2` |
| `POST /runs` 500 `NoClassDefFoundError: okhttp3/...` | okhttp 不在 classpath | 在 pom.xml 加 okhttp 依赖 |
| SSE 流前端长时间无响应 | 响应包装过滤器没跳过 /stream | 见步骤 7 |
| `POST /runs` 403 | Spring Security 没放行 `/snap-agent/**` | 见步骤 6 |
| LLM 调用超时 | cc-switch 代理不可达，或 `timeout-seconds` 太小 | 确认 `https://claudecode.sf-express.com/ccr` 可达；调大 `timeout-seconds` |
| `GET /skills` 中 skill 状态为 `UNAVAILABLE` | skill 引用的 tool 未注册（如 JDBC 未配置） | 检查 `snap-agent.jdbc.enabled`；检查 DataSource Bean 是否存在 |

---

## 内置 Skill 说明

v__VERSION__ 内置 3 个 Skill（打包在 JAR 的 classpath:/docs/skills/ 中，只读不可删除）：

| Skill | 工具 | 用途 |
|-------|------|------|
| `health-check` | `mysql_query` | 验证数据库连通性、检查表 |
| `database-query` | `mysql_query` | 自然语言转 SQL 查询业务数据 |
| `redis-query` | `redis_get` | 读取 Redis key 的值和存在性 |

运行时可通过 `POST /snap-agent/skills/upload` 上传自定义 Skill 到 `/app/deploy/skills`，重启后保留。

---

## 不要做的事

- **不要用 `system` scope 引入 jar** — 无法解析传递依赖，会导致 `NoClassDefFoundError`。
- **不要给 SnapAgent 的 DataSource 用读写账号** — 只读账号是 prompt 注入的最后一道防线。
- **不要在 `allowed-models` 里加不需要的模型** — 白名单越窄越安全。
- **不要忘记放行 `/snap-agent/runs/*/stream`** — SSE 端点被 Security 拦截会导致前端无法订阅流。
- **不要把 `/app/deploy/skills` 设为 777** — 750 即可，仅应用账号可读写。
