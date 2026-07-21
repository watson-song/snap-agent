# User Display Name SPI 设计

**日期**: 2026-07-21
**状态**: Approved
**作者**: brainstorming session

## 背景与问题

宿主应用集成 skills-agent 后,前端右上角通过 `GET /skills-agent/user-info` 接口返回的 `username` 字段显示用户标识。目前 `SnapAgentController#getUserInfo()` (`snap-agent-spring-boot-2x-starter/.../web/SnapAgentController.java:346-347`) 直接执行:

```java
info.setUserId(userId);
info.setUsername(userId);   // ← bug: 把 userId 塞进了 username 字段
```

`UserInfo` 模型本身有 `userId` 和 `username` 两个字段(`snap-agent-core/.../security/UserInfo.java:15-16`),但 controller 没有调用任何途径取真实显示名。

`SecurityGateway` SPI (`snap-agent-core/.../security/SecurityGateway.java`) 只有 `currentUserId()` 和 `hasPermission()` 两个方法,没有取显示名的接口。`currentUserId()` 在 controller 里有 30+ 处调用(审计/归属校验/限流/Cost 归属),不能改名或改语义,只能加新方法。

宿主使用标准 `org.springframework.security.core.Authentication`,期望用 `auth.getName()` 取显示名。`Authentication#getName()` 的标准实现按以下顺序解析: `principal instanceof UserDetails` → `getUsername()`,`principal instanceof AuthenticatedPrincipal` → `getName()`,`principal instanceof Principal` → `getName()`,否则 `principal.toString()`。

## 目标

1. `SecurityGateway` SPI 新增一个方法返回当前用户显示名,与 `currentUserId()` 分离
2. 默认行为向后兼容:不实现新方法时,前端显示与现状一致(显示 userId)
3. `SpringSecurityAdapter` 默认实现调 `auth.getName()`
4. 不引入配置开关(YAGNI)
5. 不破坏任何现有测试

## 非目标

- 不改 `DefaultPrincipalResolver` 反射顺序(`getId()` 仍排在 `getUsername()` 前 — userId 用于归属/审计,Long id 是正确的)
- 不动审计日志/任务归属/Cost 归属(这些用 userId)
- 不加配置开关
- 不动 `ShiroAdapter` 默认行为
- 不改前端(前端已正确读 `info.username`,是后端填错)

## 设计

### SPI 改动 (core 模块)

`snap-agent-core/src/main/java/cn/watsontech/snapagent/core/security/SecurityGateway.java` 新增 default 方法:

```java
/**
 * Return the current authenticated user's display name (real name / nickname).
 * Used for UI display; {@link #currentUserId()} is still used for ownership
 * and audit. Implementations should return {@code null} when a display name
 * is not available — the controller will fall back to the user id.
 *
 * @return display name, or {@code null} if unavailable
 */
default String currentUserName() {
    return null;
}
```

### Adapter 实现 (starter 模块)

**`SpringSecurityAdapter.java`** — 覆盖方法调 `auth.getName()`:

```java
@Override
public String currentUserName() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null) {
        return null;
    }
    String name = auth.getName();
    return (name != null && !name.isEmpty()) ? name : null;
}
```

空字符串视为 null,让 controller 走 fallback。

**`ShiroAdapter.java`** — 不覆盖。Shiro 没有等价的 `getName()` 概念,默认 null + controller fallback 保留现状。宿主可自行覆盖 bean。

### Controller 改动

`SnapAgentController.java:346-347` 改成:

```java
info.setUserId(userId);
String displayName = securityGateway.currentUserName();
info.setUsername(displayName != null ? displayName : userId);
```

### 数据流

```
前端 GET /skills-agent/user-info
  → Controller#getUserInfo()
  → securityGateway.currentUserId()    → "12345"      (审计/归属, 不变)
  → securityGateway.currentUserName()  → "Alice Wang" (新)
  → info.username = "Alice Wang"
  → 前端 #userName 元素显示 "Alice Wang"
```

前端代码 `app.js:73-74` 已正确读取 `info.username`,无需改动。

## 边界情况

| 场景 | 结果 |
|------|------|
| `auth.getName()` 返回 `""` | 视为 null, fallback 到 userId |
| `auth.getName()` 等于 userId | 无影响, 与现状一致 |
| 现有自定义 SecurityGateway bean 不实现新方法 | default 返回 null → fallback → 与现状一致 |
| SecurityContext 为空 | `currentUserId()` 已返回 null → 直接返回 authenticated=false (新方法根本不调) |
| ShiroAdapter 宿主 | 不覆盖, fallback 到 userId, 与现状一致 |

## 行为变更声明

对**使用默认 `SpringSecurityAdapter` 且不覆盖 bean**的宿主,`info.username` 会从"等于 userId"变为"`auth.getName()`"。这是**修 bug** — 字段名叫 `username` 却塞 userId 是错的。

如果宿主确实想要旧行为,覆盖 `currentUserName()` 返回 `currentUserId()` 即可显式 opt-out。不加配置开关。

## 测试

### 新增用例

**`SpringSecurityAdapterTest.java`**:
- `currentUserName()` 调 `auth.getName()` 并返回值
- `auth` 为 null 时返回 null
- `auth.getName()` 返回空串时返回 null

**`SnapAgentControllerSecurityTest.java`** (或 `SnapAgentControllerTest.java`):
- `currentUserName()` 返回 "Alice Wang" 时 `$.username` == "Alice Wang" 且 `$.userId` 不变
- `currentUserName()` 返回 null 时 `$.username` fallback 到 userId

### 现有测试影响

Mockito mock 对 default 方法默认返回 null,所以现有 `when(securityGateway.currentUserId()).thenReturn("user001")` 测试场景下,`currentUserName()` 返回 null → controller fallback → `info.username` 仍等于 "user001" → 与现状一致。**现有测试无需改动**。

`SnapAgentControllerSecurityTest` 现有断言只检查 `$.authenticated` 和 `$.authorized`,不检查 `$.username`,所以也不会破坏。

## 文档改动

- `docs/embeed-skills-agent/07-config-security.md`: 新增 `currentUserName()` SPI 说明 + 覆盖示例
- `docs/embeed-skills-agent/09-integration-guide.md`: 新增"自定义 SecurityGateway 取真实姓名"集成示例片段
- `MEMORY.md`: 在"安全权限模型坑"段落补一句 SPI 新方法

## 实现步骤概览

1. 修改 `SecurityGateway` 接口加 default 方法
2. 修改 `SpringSecurityAdapter` 覆盖方法
3. 修改 `SnapAgentController#getUserInfo()` 使用新方法
4. 新增 `SpringSecurityAdapterTest` 用例
5. 新增 controller 测试用例
6. 更新文档 (07, 09)
7. 全量跑测试验证零破坏
8. 更新 MEMORY.md
