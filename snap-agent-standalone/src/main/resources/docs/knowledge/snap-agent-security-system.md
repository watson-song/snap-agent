---
name: snap-agent-security-system
description: 安全框架 — SecurityGateway、SafeGuardAdvisor、AuditAdvisor、AuditStore、PrincipalResolver、SqlGuard
version: 3.0.0
modules:
  - snap-agent-core
  - snap-agent-spring-boot-2x-starter
author: SnapAgent
---

# SnapAgent 安全框架

## 1. 架构

```
请求 → SecurityGateway (鉴权) → SafeGuardAdvisor(50) (敏感词过滤)
     → AuditAdvisor(400) (审计记录) → SqlGuard (SQL 检查)
```

## 2. 核心 SPI

### 2.1 SecurityGateway

<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/security/SecurityGateway.java -->
```java
public interface SecurityGateway {
    String currentUserId();
    default String currentUserName() { return null; }
    boolean hasPermission(String code);
}
```

| 实现 | 模块 | 说明 |
|------|------|------|
| `SpringSecurityAdapter` | boot2x/security | Spring Security 适配 |
| `ShiroAdapter` | boot2x/security | Shiro 适配 |

`@ConditionalOnMissingBean` — 宿主可自定义 SecurityGateway bean 替换。

### 2.2 PrincipalResolver

<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/security/PrincipalResolver.java -->
```java
public interface PrincipalResolver {
    String resolve(Object principal);
}
```

| 实现 | 说明 |
|------|------|
| `DefaultPrincipalResolver` | String→直接返回, UserDetails→getUsername() |

## 3. Advisor 安全组件

### 3.1 SafeGuardAdvisor（Order=50）

<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/security/SafeGuardAdvisor.java -->
- **beforeNode**: 过滤用户输入中的敏感词（白名单优先）
- **afterNode**: 消毒 LLM 输出中的敏感内容

### 3.2 AuditAdvisor（Order=400）

<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/security/AuditAdvisor.java -->
- **afterNode("agent")**: 记录 LLM 调用（model, token counts）
- **afterNode("tools")**: 记录工具调用（name, args, result, truncated）

## 4. 审计组件

| 类 | 模块 | 说明 |
|----|------|------|
| `AuditStore` (SPI) | core/security | 审计日志存储 |
| `AuditEntry` | core/security | 审计条目（userId, action, resource, timestamp, context）|
| `InMemoryAuditStore` | boot2x/security | 内存环形缓冲实现 |
| `SecurityAuditLogger` (SPI) | core/security | 安全审计日志输出 |
| `AuditStoreAuditLogger` | boot2x/security | 基于 AuditStore 的实现 |
| `LoggingSecurityAuditLogger` | boot2x/security | 基于日志框架的实现 |
| `UserInfo` | core/security | 用户信息 DTO |

<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/security/AuditStore.java -->
<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/security/AuditEntry.java -->
<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/security/SecurityAuditLogger.java -->
<!-- source: snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/security/InMemoryAuditStore.java -->
<!-- source: snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/security/AuditStoreAuditLogger.java -->
<!-- source: snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/security/LoggingSecurityAuditLogger.java -->

## 5. 安全守卫

| 类 | 模块 | 保护对象 |
|----|------|---------|
| `SqlGuard` | boot2x/tool | SQL：禁 DROP/ALTER/CREATE/TRUNCATE/INSERT/UPDATE/DELETE/SLEEP/BENCHMARK |
| `CodePathGuard` | boot2x/tool | 代码路径：限制 projectRoot + 扩展名白名单 + 大小限制 |
| `LogPathGuard` | boot2x/tool | 日志路径：限制 allowed-paths |

## 6. 配置

```yaml
snap-agent:
  security:
    framework: auto              # auto | spring | shiro | none
    audit-log: true
    required-permission: snap-agent:access
    sensitive-words: []
    whitelist: []
```
