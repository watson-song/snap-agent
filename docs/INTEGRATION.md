# 集成指南 — 将 SnapAgent 嵌入第三方项目

> 本文档指导如何将 SnapAgent 作为嵌入式库集成到任意 Spring Boot 2.x 项目中。

## 前置条件

| 条目 | 要求 |
|------|------|
| Java | 8+ |
| Spring Boot | 2.5.x ~ 2.7.x |
| Servlet | javax.servlet (非 jakarta) |
| 构建工具 | Maven |
| 数据库 | MySQL（可选，JDBC 工具需要） |
| Redis | 3.x+（可选，Redis 工具需要） |
| LLM API | Anthropic 兼容 API 或代理 |

## 第一步：安装库到本地 Maven 仓库

```bash
git clone <repo-url> snap-agent && cd snap-agent && mvn clean install -DskipTests
```

> 如果你的项目使用私有 Nexus 仓库，将 `snap-agent-core` 和 `snap-agent-spring-boot-2x-starter` 的 JAR deploy 到 Nexus 后在宿主项目中直接引用依赖即可。

## 第二步：添加 Maven 依赖

在宿主项目的 `pom.xml` 中添加：

```xml
<dependency>
    <groupId>com.watsontech.snapagent</groupId>
    <artifactId>snap-agent-spring-boot-2x-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

Starter 会自动引入 `snap-agent-core`。以下依赖如果宿主项目已有则无需重复添加：

- `spring-boot-starter-web`（宿主已有）
- `spring-boot-starter-security`（可选，用于鉴权适配）
- `mysql-connector-java`（可选，JDBC 工具需要）
- `spring-boot-starter-data-redis`（可选，Redis 工具需要）

## 第三步：配置 application.yml

```yaml
snap-agent:
  enabled: true                          # 必须显式开启
  base-path: /snap-agent               # URL 前缀，可自定义
  skills-dir: classpath:/skills/         # Skill .md 文件目录，也支持文件系统路径
  llm:
    base-url: https://api.anthropic.com  # LLM API 地址
    api-key: ${LLM_API_KEY}              # 推荐用环境变量
    model: claude-sonnet-4-6             # 默认模型
    timeout-seconds: 120
  agent:
    max-turns: 20
    task-timeout-minutes: 30
    max-result-rows: 1000                # SQL 查询行数上限
  jdbc:
    enabled: true                        # 开启数据库查询工具
  security:
    framework: auto                      # 自动检测 Spring Security / Shiro
    audit-log: true
  routing:
    mode: none                           # 单实例用 none，多实例见下方说明
```

## 第四步：提供只读 DataSource

SnapAgent 需要**独立的只读 DataSource**，与宿主业务 DataSource 隔离，确保 Agent 无法执行写操作。

```java
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import javax.sql.DataSource;

@Configuration
public class SnapAgentDataSourceConfig {

    @Bean
    public DataSource snapAgentReadOnlyDataSource() {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl("jdbc:mysql://your-db-host:3306/your_schema?useSSL=false");
        ds.setUsername("readonly_user");        // 只读账号！
        ds.setPassword("readonly_password");
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setMaximumPoolSize(3);
        ds.setMinimumIdle(1);
        ds.setPoolName("snap-agent-jdbc");
        return ds;
    }
}
```

> **重要**：DataSource 的 Bean 名默认必须为 `snapAgentReadOnlyDataSource`。如需自定义名称，在配置中设置 `snap-agent.jdbc.datasource-bean-name`。

## 第五步：配置安全规则

SnapAgent 的端点需要与宿主应用的安全框架协调。以下是常见场景：

### 场景 A：宿主使用 Spring Security

```java
@Configuration
public class SecurityConfig extends WebSecurityConfigurerAdapter {
    @Override
    protected void configure(HttpSecurity http) throws Exception {
        http.csrf().disable()
            .authorizeRequests()
                // 公开端点（UI、Skill 列表、SSE 流）
                .antMatchers("/snap-agent/*.html", "/snap-agent/*.js",
                             "/snap-agent/*.css").permitAll()
                .antMatchers("/snap-agent/skills", "/snap-agent/models",
                             "/snap-agent/tools").permitAll()
                .antMatchers("/snap-agent/runs/*/stream").permitAll()
                .antMatchers("/snap-agent-internal/**").permitAll()
                // 需要认证的端点
                .antMatchers("/snap-agent/runs/**").authenticated()
                .anyRequest().authenticated()
            .and()
                .httpBasic();  // 或 formLogin() 或你的自定义认证
    }
}
```

> SnapAgent 的 `SpringSecurityAdapter` 会自动从 `SecurityContextHolder` 获取当前用户 ID。

### 场景 B：宿主使用 Shiro

无需额外配置，`ShiroAdapter` 会自动从 `Subject.getPrincipal()` 获取用户 ID。

### 场景 C：宿主无安全框架

实现 `PrincipalResolver` 接口并注册为 Bean：

```java
@Component
public class MyPrincipalResolver implements PrincipalResolver {
    @Override
    public String resolvePrincipal() {
        // 从你的自定义上下文返回用户 ID
        return MyContextHolder.getCurrentUserId();
    }
}
```

### 场景 D：完全跳过安全（不推荐生产使用）

```java
// 不配置任何安全框架，SnapAgent 会使用 DefaultPrincipalResolver
// 它从 HTTP Basic Auth header 解析用户名，如果没有则返回 "anonymous"
```

## 第六步：编写 Skill 文件

在 `skills-dir` 指定的目录下创建 `.md` 文件：

```markdown
---
name: order-anomaly-diagnose
description: "订单异常诊断。Use when investigating abnormal order status, missing orders, or order flow issues."
---

# 订单异常诊断流程

## Phase 1: 收集信息
向用户确认以下信息：
- 订单号 / SKU / 时间范围
- 异常现象描述

## Phase 2: 查询订单数据
使用 query_database 工具查询：

SELECT order_id, status, create_time, update_time
FROM t_order
WHERE order_id = '{orderId}'
LIMIT 10;

## Phase 3: 分析 & 报告
...
```

### Skill 文件规范

| 字段 | 说明 |
|------|------|
| `name` | Skill 唯一标识（kebab-case） |
| `description` | 描述（中英均可），LLM 用此判断何时使用该 Skill |
| `inputs` | 可选，定义结构化输入参数（key, label, type, required, options） |

### Skill 目录结构

```
skills/
├── order-diagnose.md              # 单文件 Skill
├── health-check.md
└── replenishment-strategy-diagnose/  # 文件夹 Skill（主文件必须为 SKILL.md）
    ├── SKILL.md
    └── reference-data.sql          # 附加资源文件
```

## 第七步：启动 & 验证

1. 启动宿主 Spring Boot 应用
2. 访问 `http://localhost:8080/snap-agent/` — 应看到 Chat UI
3. 访问 `http://localhost:8080/snap-agent/skills` — 应返回 Skill 列表 JSON
4. 在 UI 中选择 Skill，输入问题，开始诊断

## 多实例部署（K8s）

如果应用以多副本部署在 K8s 中，需要配置跨 Pod 路由：

```yaml
snap-agent:
  routing:
    mode: k8s-api                      # 或 headless-dns / static
    internal-token: ${INTERNAL_TOKEN}  # Pod 间共享密钥
    k8s-service-name: your-app-svc     # K8s Service 名称
    discovery-cache-ttl-seconds: 10
```

同时需要在 K8s 部署清单中注入 Pod IP：

```yaml
env:
  - name: MY_POD_IP
    valueFrom:
      fieldRef:
        fieldPath: status.podIP
```

并确保 `/snap-agent-internal/**` 路径在 Service 和 Ingress 中可被 Pod 间访问。

## 自定义工具

实现 `ToolProvider` 接口并注册为 `@Component`：

```java
@Component
public class HttpCallToolProvider implements ToolProvider {
    @Override
    public String name() { return "http_call"; }

    @Override
    public String schema() {
        return "{\"name\":\"http_call\","
             + "\"description\":\"Calls an internal API endpoint\","
             + "\"input_schema\":{\"type\":\"object\","
             + "\"properties\":{\"url\":{\"type\":\"string\"},"
             + "\"method\":{\"type\":\"string\",\"enum\":[\"GET\",\"POST\"]}},"
             + "\"required\":[\"url\",\"method\"]}}";
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
        long start = System.currentTimeMillis();
        String url = (String) args.get("url");
        String method = (String) args.get("method");
        try {
            // 你的 HTTP 调用逻辑
            String result = doHttpCall(url, method);
            return ToolResult.success(result, 0, System.currentTimeMillis() - start);
        } catch (Exception e) {
            return ToolResult.failure(e.getMessage(), System.currentTimeMillis() - start);
        }
    }
}
```

工具会自动被 `ToolDispatcher` 收集并暴露给 LLM。在 Skill 文件中通过工具名引导 LLM 使用。

## 常见问题

### Q: 页面打开空白 / JS 报错

浏览器缓存了旧版 JS。硬刷新（`Cmd+Shift+R` / `Ctrl+Shift+R`）或检查 `app.js?v=N` 版本号。

### Q: SSE 流中断

检查服务端日志是否有 `Broken pipe` 或 `Failed write`。通常是客户端关闭连接或网络超时。`snap-agent.llm.timeout-seconds` 控制单个 LLM 请求的超时。

### Q: LLM 返回 401 / 403

检查 `api-key` 或 `auth-token` 配置。如果使用代理，确认 `base-url` 指向正确的代理地址。

### Q: 数据库查询报错 "SqlGuard rejected"

SqlGuard 拒绝了非 SELECT 语句。检查 Skill 文件中的 SQL 是否只包含 SELECT/SHOW/DESCRIBE/EXPLAIN/WITH 语句。详见 `SqlGuardTest`。

### Q: 多实例下任务找不到

跨 Pod 路由未配置或配置错误。检查 `routing.mode` 和 `routing.internal-token`。确认 K8s Service 可以 Pod 间互访。

## 最佳实践

1. **数据库账号用只读**：即使 SqlGuard 拦截写操作，DataSource 也应使用只读数据库账号
2. **Skill 文件用 Git 管理**：将 skills 目录放在项目资源目录中，随代码版本管理
3. **LLM 超时设够大**：复杂诊断可能需要多轮 LLM 调用，建议 `timeout-seconds: 120`
4. **限制并发**：生产环境建议 `max-concurrent-runs-per-user: 1`，防止资源滥用
5. **监控线程池**：`snapAgentExecutor` 线程池（core=2, max=4, queue=10），高并发时需调整
