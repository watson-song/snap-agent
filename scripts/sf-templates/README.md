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
    base-url: https://claudecode.sf-express.com/ccr
    auth-token: <使用者工号>        # ← 替换为用户提供的工号
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

> - `base-url` 是 cc-switch 代理网关地址，LLM 请求通过它路由到 `aliyun/glm-5.2` 模型。
> - `auth-token` 是使用者的工号，作为 Bearer token 发送。**不要硬编码任何人的工号**，必须向用户确认后填入。
> - `upload-skills-dir: /app/deploy/skills` 是 SF 服务器上的 Skill 上传目录，重启后持久化。需确保该目录存在且应用账号有读写权限。
> - `allowed-models` 是服务端强制白名单，前端无法绕过。

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

---

## 验证集成

完成所有步骤后，启动宿主应用并依次验证：

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
| Spring Security 没放行 `/snap-agent/**` | 检查 Security 配置 | 见步骤 7，确保放行 `/snap-agent/runs/**` |
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

### 10. 项目特有的其他问题

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

v__VERSION__ 内置 3 个 Skill（打包在 JAR 的 classpath:/docs/skills/ 中，只读不可删除）：

| Skill | 工具 | 用途 |
|-------|------|------|
| `health-check` | `mysql_query` | 验证数据库连通性、检查表 |
| `database-query` | `mysql_query` | 自然语言转 SQL 查询业务数据 |
| `redis-query` | `redis_get` | 读取 Redis key 的值和存在性 |

运行时可通过 `POST /snap-agent/skills/upload` 上传自定义 Skill 到 `/app/deploy/skills`，重启后保留。

---

## 默认配置速查

| 项 | 值 |
|----|----|
| LLM 代理 | `https://claudecode.sf-express.com/ccr`（cc-switch） |
| 模型 | `aliyun/glm-5.2` |
| Auth Token | 使用者工号（Bearer，部署时由 AI 询问后填入） |
| Skill 上传目录 | `/app/deploy/skills` |
| 内置 Skill | health-check, database-query, redis-query |
| Web UI | `http://<host>:<port>/snap-agent/` |
| API 基础路径 | `/snap-agent` |

---

## 不要做的事

- **不要用 `system` scope 引入 jar** — 无法解析传递依赖，会导致 `NoClassDefFoundError`。
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
