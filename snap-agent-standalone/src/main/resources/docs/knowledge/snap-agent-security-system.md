---
name: snap-agent-security-system
description: 安全框架 — SecurityGateway、SqlGuard、AuditAdvisor、AuditStore、PrincipalResolver
version: 2.0.0
modules:
  - snap-agent-core
  - snap-agent-spring-boot-2x-starter
author: SnapAgent
---

# SnapAgent 安全框架

## 1. 架构

```
请求 → SecurityGateway (鉴权) → SqlGuard (SQL 检查) → AuditAdvisor (审计记录)
```

## 2. SecurityGateway SPI

```java
// core/security/SecurityGateway.java
public interface SecurityGateway {
    String currentUserId();
    default String currentUserName() { return null; }
    boolean hasPermission(String permission);
}
```

| 实现 | 模块 | 说明 |
|------|------|------|
| `SpringSecurityAdapter` | boot2x/security | Spring Security 适配 |
| `ShiroAdapter` | boot2x/security | Shiro 适配 |

`@ConditionalOnMissingBean` — 宿主可自定义 SecurityGateway bean 替换。

## 3. PrincipalResolver

```java
// core/security/PrincipalResolver.java
public interface PrincipalResolver {
    String resolve(Object principal);
}
```

| 实现 | 说明 |
|------|------|
| `DefaultPrincipalResolver` | String→直接返回, UserDetails→getUsername() |

## 4. SqlGuard

```java
// boot2x/tool/SqlGuard.java
// 禁止: DROP/ALTER/CREATE/TRUNCATE/INSERT/UPDATE/DELETE/REPLACE/SLEEP/BENCHMARK/LOAD_FILE/INTO OUTFILE
```

## 5. AuditAdvisor (Order=400)

```java
// core/security/AuditAdvisor.java
// beforeNode: 记录操作开始
// afterNode: 记录操作完成
```

| 组件 | 说明 |
|------|------|
| `AuditStore` (SPI) | 审计日志存储 |
| `AuditEntry` | 审计条目（userId, action, resource, timestamp, context）|
| `InMemoryAuditStore` | 内存环形缓冲实现 |
| `SecurityAuditLogger` (SPI) | 安全审计日志输出 |
| `AuditStoreAuditLogger` | 基于 AuditStore 的实现 |
| `LoggingSecurityAuditLogger` | 基于日志框架的实现 |
| `UserInfo` | 用户信息 DTO |

## 6. 配置

```yaml
snap-agent:
  security:
    framework: auto  # auto | spring | shiro | none
    audit-log: true
    required-permission: snap-agent:access
```
