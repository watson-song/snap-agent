# 09 — 宿主集成指南

集成 = 5 步。无宿主代码改动（除第 5 步可选的 ~5 行 PrincipalResolver）。

## 步骤 1：加 pom

```xml
<dependency>
  <groupId>com.watsontech.snapagent</groupId>
  <artifactId>snap-agent-spring-boot-2x-starter</artifactId>
  <version>1.0.0</version>
</dependency>
```

- starter 自动依赖 `snap-agent-core`。
- 宿主须已是 Spring Boot 2.x（`javax.servlet`）。Spring Boot 3.x 等 Phase 3 的 3x-starter。
- 不需要额外引 OkHttp / snakeyaml：版本由 Spring Boot BOM 管控（决策 #17）。

### 步骤 1b：宿主自有 Skill 打包配置（如有）

SnapAgent 内置 Skill 打包在 starter JAR 的 `classpath:/docs/skills/` 中。如果宿主项目也有自己的 Skill 文件放在 `docs/skills/` 下，需要将该目录加入 Maven 资源打包，否则 `docs/` 不是标准资源目录，不会出现在 classpath 中。

检查宿主项目是否有 `docs/skills/` 目录及 `.md` 文件（含子目录中的 `SKILL.md`）：

```bash
find docs/skills -name "*.md" 2>/dev/null | head -5
# 有输出 → 需配置；无输出 → 跳过此步
```

有则在宿主 `pom.xml` 的 `<build>` → `<resources>` 中添加（与现有配置合并，不覆盖）：

```xml
<build>
    <resources>
        <resource>
            <directory>src/main/resources</directory>
        </resource>
        <!-- 把 docs/skills/ 下的 .md 打入 classpath:/docs/skills/ -->
        <!-- 多模块项目 docs/ 在根目录时改为 ${maven.multiModuleProjectDirectory}/docs -->
        <resource>
            <directory>docs</directory>
            <targetPath>docs</targetPath>
            <includes>
                <include>skills/**/*.md</include>
            </includes>
        </resource>
    </resources>
</build>
```

打包后宿主 Skill 与 SnapAgent 内置 Skill 合并在同一个 `classpath:/docs/skills/` 路径下，同名时宿主的覆盖 starter 的。无自有 Skill 则跳过此步。

## 步骤 2：配 yml

最小可用配置：
```yaml
snap-agent:
  enabled: true
  builtin-skills-dir: classpath:/docs/skills/   # builtin skills, 打包在 JAR 内
  upload-skills-dir: /opt/snap-agent/skills/    # 用户上传/自定义 skill, 文件系统目录
  llm:
    api-key: ${LLM_API_KEY}              # 必填，否则 LlmClient 不装配
    model: claude-sonnet-4-6
    allowed-models: [claude-sonnet-4-6]
  jdbc:
    enabled: true
    datasource-bean-name: snapAgentReadOnlyDataSource
  redis:
    enabled: false                       # 不需要 Redis 工具就关
  security:
    required-permission: ""              # 已登录即可；建议配 admin 权限码
```

### 独立只读 DataSource bean（宿主侧声明）

宿主在某 `@Configuration` 里声明这个 bean（用只读 DB 账号）：
```java
@Bean
public DataSource snapAgentReadOnlyDataSource(
        @Value("${snap-agent.db.read-only.url}") String url,
        @Value("${snap-agent.db.read-only.username}") String user,
        @Value("${snap-agent.db.read-only.password}") String pass) {
    HikariDataSource ds = new HikariDataSource();
    ds.setJdbcUrl(url);
    ds.setUsername(user);
    ds.setPassword(pass);
    ds.setReadOnly(true);                 // 连接级只读
    ds.setMaximumPoolSize(2);             # 小池子，agent 用量不大
    return ds;
}
```
配套 `application.yml`：
```yaml
snap-agent:
  db:
    read-only:
      url: jdbc:mysql://host:3306/your-app?...
      username: readonly_user
      password: ${RO_DB_PASSWORD}
```

## 步骤 3：建只读 DB 用户

运维在 DB 上建受限只读账号：
```sql
-- 1. 建用户
CREATE USER 'readonly_user'@'%' IDENTIFIED BY '<strong-pass>';

-- 2. 仅授诊断所需表的 SELECT（按需列清单，不授敏感表/列）
GRANT SELECT ON your_db.sep_wh_replenish TO 'readonly_user'@'%';
GRANT SELECT ON your_db.sep_wh_replenish_extend TO 'readonly_user'@'%';
GRANT SELECT ON your_db.drp_replenishment_strategy_parameters TO 'readonly_user'@'%';
GRANT SELECT ON your_db.drp_replenishment_strategy_parameters_extend TO 'readonly_user'@'%';
GRANT SELECT ON your_db.drp_sku_detail TO 'readonly_user'@'%';
GRANT SELECT ON your_db.drp_warehouse TO 'readonly_user'@'%';
GRANT SELECT ON your_db.drp_sku_warehouse_network TO 'readonly_user'@'%';
GRANT SELECT ON your_db.drp_sku_stock_network TO 'readonly_user'@'%';
GRANT SELECT ON your_db.dws_replm_invn_sum TO 'readonly_user'@'%';
GRANT SELECT ON your_db.dws_alg_allocation_input TO 'readonly_user'@'%';
GRANT SELECT ON your_db.dws_alg_allocation_output TO 'readonly_user'@'%';
GRANT SELECT ON your_db.drp_allocation_plan TO 'readonly_user'@'%';
GRANT SELECT ON your_db.sys_batch_log TO 'readonly_user'@'%';
GRANT SELECT ON your_db.sale_order TO 'readonly_user'@'%';
GRANT SELECT ON your_db.maintenance_plan_task TO 'readonly_user'@'%';
-- 视图（如 vw_drp_sku_stock_network_scope / vw_drp_sku_substitute）按需授
-- 显式不授：drp_sys_user / 权限表 / 密码列 / 任何含 PII 的表

-- 3. 撤销隐式权限
REVOKE ALL PRIVILEGES ON your_db.* FROM 'readonly_user'@'%';
-- （MySQL 8 起默认无隐式权限，但确认一下）

-- 4. 限制连接级只读（额外保险）
-- Hikari 的 setReadOnly(true) 已做；DB 侧也可：
ALTER USER 'readonly_user'@'%' DEFAULT ROLE NONE;
```

**关键原则**（呼应 [04](04-tools-and-mcp.md) §2.5 局限）：
- **不授** `drp_sys_user`、权限表、密码列、PII 表。SQL guard 允许 `SELECT password FROM drp_sys_user`，但只读 DSN 无该表权限 → DB 拒绝。
- 只授诊断 skill 真正查询的表（见现有 3 个 skill 的 SQL 涉及的表）。
- 多租户：若 DB 支持 RLS（Row-Level Security），按 tenant_id 限制行；或只授本租户可见视图。

## 步骤 4：放行 `/snap-agent/**`

本库 filter 不做认证，依赖宿主安全框架已认证。宿主须在安全配置里放行该路径（已认证才能进）。

> **重要**：所有 `/snap-agent/**` 端点（包括 `/snap-agent/user-info`）都需要认证。不要将 `/snap-agent/user-info` 加入 JWT filter 的白名单/跳过列表，否则 SecurityContext 不会被填充，SnapAgent 会看到 `anonymousUser`。前端已经处理 401 响应——未登录时显示"登录失效"提示。

### 前后端分离（Token 鉴权）配置

当前后端分离项目使用 token header（如 JWT）而非 session 鉴权时，需配置 SnapAgent 前端从 localStorage 或 cookie 读取 token 并以 header 发送：

```yaml
snap-agent:
  security:
    auth-token-header: token              # 宿主 API 期望的 token header 名称
    auth-token-local-storage-key: TOKEN   # 宿主前端存储 token 的 localStorage key
    # 或使用 cookie: auth-token-cookie: a_authorization
```

配置后，SnapAgent 前端会：
1. 启动时调用 `GET /snap-agent/auth-config`（公开端点）获取配置
2. 从指定 localStorage key（优先）或 cookie 读取 token
3. 以指定 header 名称注入到所有 API fetch 请求

未配置时（默认），前端依赖浏览器自动携带 cookie（适用于 session 鉴权项目）。

### Spring Security
```java
@Override
protected void configure(HttpSecurity http) throws Exception {
    http
        .authorizeRequests()
            // 公开：前端鉴权配置（无敏感信息）
            .antMatchers("/snap-agent/auth-config").permitAll()
            // 静态资源放行（SPA 壳页面）
            .antMatchers("/snap-agent/*.html", "/snap-agent/*.js",
                    "/snap-agent/*.css").permitAll()
            // SSE 流端点放行（EventSource 无法发送自定义 header；controller 校验 task ownership）
            .antMatchers("/snap-agent/runs/*/stream").permitAll()
            // 其余所有 /snap-agent/** 端点（含 /user-info）需认证
            .antMatchers("/snap-agent/**").authenticated()
            // 或 .antMatchers("/snap-agent/**").hasAuthority("snap-agent:access")
            ...
        ;
}
```
> 本库 filter order = `Ordered.LOWEST_PRECEDENCE - 10`（≈2147483637），在 Spring Security 链（order -100）之后，principal 已就绪。

### Shiro
```java
@Bean
public ShiroFilterChainDefinition shiroFilterChainDefinition() {
    DefaultShiroFilterChainDefinition chain = new DefaultShiroFilterChainDefinition();
    chain.addPathDefinition("/snap-agent/auth-config", "anon");  // 前端鉴权配置
    chain.addPathDefinition("/snap-agent/*.html", "anon");   // 静态资源
    chain.addPathDefinition("/snap-agent/*.js", "anon");
    chain.addPathDefinition("/snap-agent/*.css", "anon");
    chain.addPathDefinition("/snap-agent/runs/*/stream", "anon");  // SSE
    chain.addPathDefinition("/snap-agent/**", "authc");    // 其余需登录
    // 或 chain.addPathDefinition("/snap-agent/**", "perms[snap-agent:access]");
    chain.addPathDefinition("/**", "anon");
    return chain;
}
```

### 反向代理（SSE 兼容）
Nginx：
```nginx
location /snap-agent/ {
    proxy_pass http://app:8080;
    proxy_buffering off;            # SSE 必须
    proxy_read_timeout 3600s;       # 长连接
    proxy_set_header X-Accel-Buffering no;   # 响应头也带
    proxy_set_header Connection '';
    proxy_http_version 1.1;
    chunked_transfer_encoding on;
}
```

## 步骤 5：（可能需要）PrincipalResolver

SnapAgent 需要从 SecurityContext 的 principal 对象中解析出用户标识（userId）。默认 `DefaultPrincipalResolver` 按以下顺序解析：

1. `principal instanceof String` → 直接返回
2. `principal instanceof UserDetails` → 调用 `getUsername()`
3. 反射调用 `getId()` / `getUserId()` / `getUsername()` / `getUserName()`（按序尝试，返回第一个非 null 值；非 String 类型自动 `toString()` 转换）

**常见需要自定义的场景**：

| 场景 | 是否需要自定义 | 原因 |
|------|--------------|------|
| principal 是 String（JWT/CAS 常见） | 否 | 步骤 1 直接命中 |
| principal 实现 UserDetails 且 `getUsername()` 返回用户标识 | 否 | 步骤 2 命中 |
| principal 有 `getId()` 返回 Long | 否 | 步骤 3 反射 + `toString()` 自动转换 |
| principal 有 `getUserName()`（Lombok `userName` 字段） | 否 | 步骤 3 反射命中 `getUserName` |
| principal 的用户标识字段名不在上述列表中（如 `getEmpNo()`、`getStaffId()`） | **是** | 默认反射不覆盖 |
| principal 的 `getId()` 返回数据库自增 ID 而非业务用户标识，且需要业务标识 | **是** | 需跳过 `getId()` 取其他字段 |

**自定义方式**（二选一）：

方式 A：yml 配置类名
```java
public class AppPrincipalResolver implements PrincipalResolver {
    @Override
    public String resolve(Object principal) {
        if (principal instanceof AppUserDetails u) return u.getEmpNo();
        return null;
    }
}
```
```yaml
snap-agent:
  security:
    principal-resolver-class: com.sf.your_db.sys.security.AppPrincipalResolver
```

方式 B：声明 `@Bean`（优先级高于默认实现，`@ConditionalOnMissingBean` 生效）
```java
@Bean
public PrincipalResolver snapAgentPrincipalResolver() {
    return principal -> {
        if (principal instanceof LoginUser u) return u.getUserName();
        if (principal instanceof String s) return s;
        return null;
    };
}
```

> 方式 B 无需 yml 配置，SnapAgent 自动检测到 bean 后跳过默认实现。

## 故障排查

| 现象 | 排查 |
|------|------|
| `GET /skills` 404 | `enabled` 未设 true；或 `base-path` 被宿主 controller 抢占；或宿主 security 没放行 |
| 所有 skill `UNAVAILABLE` | LlmClient 未装配（`api-key` 空）；或所有 ToolProvider 未装配（JDBC/Redis bean 名不对） |
| `mysql_query` 调用报「DataSource bean 'xxx' not found」 | `jdbc.datasource-bean-name` 与宿主声明的 bean 名不一致 |
| `mysql_query` 报 SQL 被拒 | 看 `unavailableReason` / 日志，对照 [04](04-tools-and-mcp.md) §2.4 拒绝用例 |
| SSE 收不到事件 / 一次性全到 | 反向代理 buffering 未关；Nginx 加 `proxy_buffering off` |
| 401「无法解析 principal」 | 默认 resolver 取不到 userId；看日志 `Cannot resolve principal of type XXX`；走步骤 5 配自定义 PrincipalResolver |
| `user-info` 返回 `authenticated: false, userId: null` | ① `/snap-agent/user-info` 在 JWT filter 白名单中 → SecurityContext 未填充（移出白名单）；② principal 对象无 `getId`/`getUserId`/`getUsername`/`getUserName` 方法 → 配自定义 PrincipalResolver（步骤 5） |
| 429 RATE_LIMITED | 每用户并发 1 或每小时 20 上限；等 `Retry-After` 或调 yml |
| agent 一直转不出报告 | 可能命中 max-turns；调大 `agent.max-turns`；或 LLM 卡住，查 transcript |

## 部署 skill 文件

skill 分两层来源，部署方式不同：

- **Builtin skills**：打包在 starter JAR 内（`classpath:/docs/skills/`），无需文件系统部署，升级 starter 即可更新。
- **Upload skills（自定义）**：部署到 `upload-skills-dir`（推荐 `/opt/snap-agent/skills/`，或临时用 `/tmp/snap-agent-skills/`），目录权限 750，应用账号可读写。
- 除文件系统部署外，也可通过上传 API 动态管理自定义 skill：
  - `POST /snap-agent/skills/upload` — 上传单个 `.md` 或 `.zip` skill 文件。
  - `POST /snap-agent/skills/upload-folder` — 上传整个 skill 目录（多文件，含目录型 skill）。
  - `DELETE /snap-agent/skills/{name}` — 删除自定义 skill；若该名称被 custom 覆盖 builtin，删除后恢复 builtin 版本。builtin skill 不可删除（403）。
- 改动后 `POST /skills/refresh`（或重启）生效；上传 API 自动触发刷新。
- skill frontmatter 必须 `name`/`description`/`tools` 齐全，否则标 `INVALID`（见 [02](02-skill-loading.md) §3）。

## 安全检查清单（集成前）

- [ ] 只读 DB 用户仅授诊断表 SELECT，无敏感表/列
- [ ] Hikari `setReadOnly(true)`
- [ ] 宿主 security 放行 `/snap-agent/**`（已认证）
- [ ] `required-permission` 按需配权限码（不要空着给所有人）
- [ ] 反向代理 SSE 兼容（buffering off）
- [ ] LLM api-key 用环境变量，不硬编码
- [ ] 限流配置评估（默认并发 1 / 每小时 20，按运维规模调）
- [ ] 多租户宿主：评估只读 DSN 是否带 RLS / 视图限制
- [ ] 审计开关开（默认开），定期抽查 `GET /runs/{id}/transcript`

## 集成注意事项：响应包装过滤器

若宿主项目已有响应包装过滤器（如 `BizApiResponseWrapper`，将所有响应包成 `{ "code":0, "data":... }` 结构），**必须**跳过 `/stream` 路径，否则 SSE 实时流会被包装破坏、浏览器无法逐事件解析。

示例（宿主 Filter 内）：
```java
String requestUri = ((HttpServletRequest) request).getRequestURI();
if (requestUri != null && requestUri.contains("/stream")) {
    chain.doFilter(request, response);   // 原样放行，不包装
    return;
}
// 其余路径正常包装
```

- 仅 `/stream` 路径需豁免；`/skills`、`/runs` 等 REST 端点被包装无影响（前端按包装格式解析即可）。
- 若宿主用 `@ControllerAdvice` / ResponseBodyAdvice 做统一包装，同理需排除 SSE 返回类型（`text/event-stream`）。
