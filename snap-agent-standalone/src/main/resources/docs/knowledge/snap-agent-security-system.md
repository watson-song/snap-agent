---
name: snap-agent-security-system
description: 安全框架详解 — SqlGuard、AuditStore、SecurityGateway、JWT 鉴权
version: 1.0.0
modules:
  - snap-agent-core
  - snap-agent-spring-boot-2x-starter
author: SnapAgent
---

# SnapAgent 安全框架

## 1. 架构概述

SnapAgent 采用多层安全机制，保护系统免受恶意操作：

```
┌─────────────────────────────────────────────────────────
│                    用户请求                               │
└────────────────────────┬────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────┐
│              SecurityGateway (鉴权层)                    │
│  - 用户认证（Basic Auth / JWT）                          │
│  - 权限检查（hasPermission）                             │
│  - PrincipalResolver（用户 ID 解析）                      │
└────────────────────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────┐
│                SqlGuard (SQL 安全层)                     │
│  - 禁止 DDL（DROP/ALTER/CREATE）                         │
│  - 禁止 DML（INSERT/UPDATE/DELETE）                      │
│  - 禁止危险函数（SLEEP/BENCHMARK）                       │
└────────────────────────┬────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────┐
│              AuditStore (审计层)                         │
│  - 记录所有操作日志                                      │
│  - 用户 ID + 时间戳 + 操作类型                          │
│  - 内存环形缓冲（1000 条）                                │
└─────────────────────────────────────────────────────────┘
```

## 2. SecurityGateway

### 2.1 接口定义

```java
public interface SecurityGateway {
    /** 获取当前用户 ID */
    String currentUserId();
    
    /** 获取当前用户名 */
    String currentUserName();
    
    /** 检查用户权限 */
    boolean hasPermission(String permission);
}
```

### 2.2 Spring Security 实现

```java
public class SpringSecurityAdapter implements SecurityGateway {
    private final PrincipalResolver principalResolver;

    @Override
    public String currentUserId() {
        Object principal = SecurityContextHolder.getContext()
            .getAuthentication().getPrincipal();
        return principalResolver.resolve(principal);
    }

    @Override
    public boolean hasPermission(String permission) {
        Authentication auth = SecurityContextHolder.getContext()
            .getAuthentication();
        return auth.getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals(permission));
    }
}
```

## 3. SqlGuard

### 3.1 SQL 检查规则

```java
public class SqlGuard {
    private static final List<String> DANGEROUS_PATTERNS = Arrays.asList(
        "DROP", "ALTER", "CREATE", "TRUNCATE",
        "INSERT", "UPDATE", "DELETE", "REPLACE",
        "SLEEP", "BENCHMARK", "LOAD_FILE", "INTO OUTFILE"
    );

    public void check(String sql) {
        String upper = sql.toUpperCase().trim();
        for (String pattern : DANGEROUS_PATTERNS) {
            if (upper.contains(pattern)) {
                throw new IllegalArgumentException(
                    "Dangerous SQL pattern detected: " + pattern);
            }
        }
    }
}
```

### 3.2 使用场景

```java
public class JdbcQueryTool implements ToolCallback {
    private final SqlGuard sqlGuard;

    @Override
    public Object execute(Map<String, Object> input) {
        String sql = (String) input.get("sql");
        sqlGuard.check(sql);  // 安全检查
        // 执行查询...
    }
}
```

## 4. AuditStore

### 4.1 内存实现

```java
public class InMemoryAuditStore implements AuditStore {
    private final ArrayBlockingQueue<AuditEntry> buffer;

    public InMemoryAuditStore(int capacity) {
        this.buffer = new ArrayBlockingQueue<>(capacity);
    }

    @Override
    public void record(AuditEntry entry) {
        if (!buffer.offer(entry)) {
            buffer.poll();  // 移除最旧记录
            buffer.offer(entry);
        }
    }

    @Override
    public List<AuditEntry> query(String userId, String action, 
                                   int limit, int offset) {
        // 查询逻辑...
    }
}
```

### 4.2 审计日志

```java
public class AuditEntry {
    private String userId;
    private String action;  // "tool_execute", "run_create", etc.
    private String resource;
    private Instant timestamp;
    private Map<String, Object> context;
}
```

## 5. PrincipalResolver

### 5.1 接口定义

```java
public interface PrincipalResolver {
    /**
     * 从认证对象解析用户 ID
     * @param principal 认证主体（String/UserDetails/自定义对象）
     * @return 用户 ID
     */
    String resolve(Object principal);
}
```

### 5.2 默认实现

```java
public class DefaultPrincipalResolver implements PrincipalResolver {
    @Override
    public String resolve(Object principal) {
        if (principal instanceof String) {
            return (String) principal;
        }
        if (principal instanceof UserDetails) {
            return ((UserDetails) principal).getUsername();
        }
        return principal.toString();
    }
}
```

## 6. 配置属性

```yaml
snap-agent:
  security:
    framework: auto  # auto | spring | shiro | none
    audit-log: true
    required-permission: snap-agent:access
    plugin-read-permission: ""
    plugin-manage-permission: ""
    auth-token-header: ""
    auth-token-cookie: ""
    auth-token-local-storage-key: ""
```

## 7. JWT 鉴权

### 7.1 Token 生成

```java
public class JwtTokenProvider {
    private final SecretKey secretKey;
    
    public String createToken(String userId, List<String> roles) {
        return Jwts.builder()
            .setSubject(userId)
            .claim("roles", roles)
            .setIssuedAt(new Date())
            .setExpiration(new Date(System.currentTimeMillis() + 86400000))
            .signWith(secretKey, SignatureAlgorithm.HS256)
            .compact();
    }
}
```

### 7.2 Token 验证

```java
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) {
        String token = extractToken(request);
        if (token != null) {
            Claims claims = parseToken(token);
            // 设置 SecurityContext...
        }
        chain.doFilter(request, response);
    }
}
```

## 8. 常见问题

### Q1: SQL 被拒绝？
```
错误：Dangerous SQL pattern detected: DROP
原因：SqlGuard 检测到危险操作
解决：只允许 SELECT 查询
```

### Q2: 权限不足？
```
错误：Permission denied: snap-agent:access
原因：用户未授权
解决：在 SecurityConfig 中添加权限
```

### Q3: JWT 过期？
```
错误：JWT token expired
原因：Token 超过有效期（默认 24 小时）
解决：重新获取 Token
```
