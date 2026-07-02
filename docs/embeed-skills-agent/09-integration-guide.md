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

## 步骤 2：配 yml

最小可用配置：
```yaml
snap-agent:
  enabled: true
  skills-dir: file:/opt/skills/          # 或 classpath:/skills/
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

### Spring Security
```java
@Override
protected void configure(HttpSecurity http) throws Exception {
    http
        .authorizeRequests()
            .antMatchers("/snap-agent/**").authenticated()   // 已登录即可
            // 或 .antMatchers("/snap-agent/**").hasAuthority("snap-agent:run")
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
    chain.addPathDefinition("/snap-agent/**", "authc");    // 已登录
    // 或 chain.addPathDefinition("/snap-agent/**", "perms[snap-agent:run]");
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

## 步骤 5：（可选）PrincipalResolver

若默认 `PrincipalResolver`（String→UserDetails→反射 getId/getUserId/getUsername）取不到 userId：
1. 宿主写实现：
```java
public class AppPrincipalResolver implements PrincipalResolver {
    @Override
    public String resolve(Object principal) {
        if (principal instanceof AppUserDetails u) return u.getUserId();
        return null;
    }
}
```
2. yml 配：
```yaml
snap-agent:
  security:
    principal-resolver-class: com.sf.your_db.sys.security.AppPrincipalResolver
```

这是**唯一**可能需要宿主写代码的点。多数 JWT/CAS 场景 principal 是 String，默认实现就够。

## 故障排查

| 现象 | 排查 |
|------|------|
| `GET /skills` 404 | `enabled` 未设 true；或 `base-path` 被宿主 controller 抢占；或宿主 security 没放行 |
| 所有 skill `UNAVAILABLE` | LlmClient 未装配（`api-key` 空）；或所有 ToolProvider 未装配（JDBC/Redis bean 名不对） |
| `mysql_query` 调用报「DataSource bean 'xxx' not found」 | `jdbc.datasource-bean-name` 与宿主声明的 bean 名不一致 |
| `mysql_query` 报 SQL 被拒 | 看 `unavailableReason` / 日志，对照 [04](04-tools-and-mcp.md) §2.4 拒绝用例 |
| SSE 收不到事件 / 一次性全到 | 反向代理 buffering 未关；Nginx 加 `proxy_buffering off` |
| 401「无法解析 principal」 | 默认 resolver 取不到 userId；走步骤 5 配自定义 |
| 429 RATE_LIMITED | 每用户并发 1 或每小时 20 上限；等 `Retry-After` 或调 yml |
| agent 一直转不出报告 | 可能命中 max-turns；调大 `agent.max-turns`；或 LLM 卡住，查 transcript |

## 部署 skill 文件

- `skills-dir: file:/opt/skills/`，目录 750，应用账号可读。
- admin 通过部署流水线（GitOps / ConfigMap）写入 `*.md`。
- 改动后 `POST /skills/refresh`（或重启）生效。
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
