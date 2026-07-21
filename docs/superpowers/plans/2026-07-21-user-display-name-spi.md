# User Display Name SPI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `SecurityGateway` SPI 上新增 `currentUserName()` default 方法,让 controller 的 `/user-info` 端点返回真实显示名(通过 `auth.getName()`),同时保留向后兼容(null 时 fallback 到 userId)。

**Architecture:** SPI 新增一个 default 方法,`SpringSecurityAdapter` 覆盖它调用 `Authentication#getName()`,`ShiroAdapter` 不覆盖(默认 null + controller fallback)。Controller 在 `getUserInfo()` 里调用新方法填充 `UserInfo.username`,null 时 fallback 到 userId。

**Tech Stack:** Java 8, Spring Security (Authentication/SecurityContextHolder), JUnit 5, Mockito, Spring Boot MockMvc。

参考 spec: `docs/superpowers/specs/2026-07-21-user-display-name-spi-design.md`

---

## 文件清单

| 文件 | 操作 | 责任 |
|------|------|------|
| `snap-agent-core/src/main/java/cn/watsontech/snapagent/core/security/SecurityGateway.java` | 修改 | SPI 接口加 default `currentUserName()` |
| `snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/security/SpringSecurityAdapter.java` | 修改 | 覆盖方法,调 `auth.getName()` + null/空串处理 |
| `snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/web/SnapAgentController.java` | 修改 | `getUserInfo()` 改用 `currentUserName()`,null fallback userId |
| `snap-agent-spring-boot-2x-starter/src/test/java/cn/watsontech/snapagent/boot2x/security/SpringSecurityAdapterTest.java` | 修改 | 加 3 个用例 (happy/null auth/empty name) |
| `snap-agent-spring-boot-2x-starter/src/test/java/cn/watsontech/snapagent/boot2x/web/SnapAgentControllerSecurityTest.java` | 修改 | 加 2 个用例 (display name / null fallback) |
| `docs/embeed-skills-agent/07-config-security.md` | 修改 | §2/§3.1/§3.2 补充 `currentUserName()` 说明 |
| `docs/embeed-skills-agent/09-integration-guide.md` | 修改 | §5b 加覆盖 `currentUserName()` 示例 |
| `/Users/HuaSheng.Song/.claude/projects/-Users-HuaSheng-Song-IdeaProjects-skills-agent/memory/MEMORY.md` | 修改 | "安全权限模型坑" 段补一句 |

---

## Task 1: 在 `SecurityGateway` SPI 加 default 方法

**Files:**
- Modify: `snap-agent-core/src/main/java/cn/watsontech/snapagent/core/security/SecurityGateway.java`

- [ ] **Step 1: 修改接口,在 `currentUserId()` 方法后加 default `currentUserName()`**

在 `boolean hasPermission(String code);` 之前插入新方法,完整修改后的接口如下:

```java
package cn.watsontech.snapagent.core.security;

/**
 * Security gateway SPI — bridges the agent library to the host's security
 * framework (Spring Security or Shiro).
 *
 * <p>Implementations live in the starter module:
 * {@code SpringSecurityAdapter} and {@code ShiroAdapter}, selected via
 * {@code @ConditionalOnClass}.</p>
 *
 * <p>Authentication is delegated to the host; this interface only reads
 * the already-authenticated principal and checks permissions.</p>
 */
public interface SecurityGateway {

    /**
     * Return the current authenticated user id.
     *
     * @return the user id, or {@code null} if not authenticated
     */
    String currentUserId();

    /**
     * Return the current authenticated user's display name (real name /
     * nickname). Used for UI display; {@link #currentUserId()} is still
     * used for ownership and audit. Implementations should return
     * {@code null} when a display name is not available — the controller
     * will fall back to the user id.
     *
     * @return display name, or {@code null} if unavailable
     */
    default String currentUserName() {
        return null;
    }

    /**
     * Check whether the current user has the given permission code.
     *
     * @param code permission code; when empty, returns {@code true}
     * @return {@code true} if the user has the permission
     */
    boolean hasPermission(String code);
}
```

- [ ] **Step 2: 验证编译**

Run: `cd /Users/HuaSheng.Song/IdeaProjects/skills-agent && mvn -pl snap-agent-core compile -q`
Expected: 编译成功,无错误

- [ ] **Step 3: 提交**

```bash
cd /Users/HuaSheng.Song/IdeaProjects/skills-agent
git add snap-agent-core/src/main/java/cn/watsontech/snapagent/core/security/SecurityGateway.java
git commit -m "feat(security): add currentUserName() default method to SecurityGateway SPI"
```

---

## Task 2: TDD — `SpringSecurityAdapter.currentUserName()`

**Files:**
- Test: `snap-agent-spring-boot-2x-starter/src/test/java/cn/watsontech/snapagent/boot2x/security/SpringSecurityAdapterTest.java`
- Modify: `snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/security/SpringSecurityAdapter.java`

- [ ] **Step 1: 写 3 个失败的测试**

在 `SpringSecurityAdapterTest.java` 的最后一个 `@Test` 方法(`shouldUsePrincipalResolverForNonStringPrincipal`)之后、`private void setAuthentication(...)` 辅助方法之前,插入下面三个测试:

```java
    @Test
    void shouldReturnNameFromAuthenticationWhenAvailable() {
        Authentication auth = mock(Authentication.class);
        when(auth.getName()).thenReturn("Alice Wang");
        SecurityContextHolder.getContext().setAuthentication(auth);

        assertThat(adapter.currentUserName()).isEqualTo("Alice Wang");
    }

    @Test
    void shouldReturnNullForCurrentUserNameWhenNoAuthentication() {
        assertThat(adapter.currentUserName()).isNull();
    }

    @Test
    void shouldReturnNullWhenAuthGetNameReturnsEmptyString() {
        Authentication auth = mock(Authentication.class);
        when(auth.getName()).thenReturn("");
        SecurityContextHolder.getContext().setAuthentication(auth);

        assertThat(adapter.currentUserName()).isNull();
    }
```

- [ ] **Step 2: 运行测试验证失败**

Run: `cd /Users/HuaSheng.Song/IdeaProjects/skills-agent && mvn -pl snap-agent-spring-boot-2x-starter test -Dtest=SpringSecurityAdapterTest -q`
Expected: 3 个新测试失败
- `shouldReturnNameFromAuthenticationWhenAvailable` → 期望 "Alice Wang" 实际 `null` (default 方法未覆盖)
- `shouldReturnNullForCurrentUserNameWhenNoAuthentication` → 通过(巧合,因为 default 返回 null)
- `shouldReturnNullWhenAuthGetNameReturnsEmptyString` → 通过(巧合)

注: 第二、三个测试可能"意外通过"因为 default 方法本来就返回 null。第一个测试是真正驱动实现的红测试。这是正常的 — 后两个测试是回归保护,防止后续重构破坏 null/空串处理。

- [ ] **Step 3: 在 `SpringSecurityAdapter.java` 实现 `currentUserName()`**

在 `currentUserId()` 方法之后、`hasPermission()` 方法之前插入:

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

- [ ] **Step 4: 运行测试验证通过**

Run: `cd /Users/HuaSheng.Song/IdeaProjects/skills-agent && mvn -pl snap-agent-spring-boot-2x-starter test -Dtest=SpringSecurityAdapterTest -q`
Expected: 全部 11 个测试通过(原 8 个 + 新 3 个)

- [ ] **Step 5: 提交**

```bash
cd /Users/HuaSheng.Song/IdeaProjects/skills-agent
git add snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/security/SpringSecurityAdapter.java snap-agent-spring-boot-2x-starter/src/test/java/cn/watsontech/snapagent/boot2x/security/SpringSecurityAdapterTest.java
git commit -m "feat(security): SpringSecurityAdapter.currentUserName() uses auth.getName()"
```

---

## Task 3: TDD — Controller `getUserInfo()` 使用 `currentUserName()`

**Files:**
- Test: `snap-agent-spring-boot-2x-starter/src/test/java/cn/watsontech/snapagent/boot2x/web/SnapAgentControllerSecurityTest.java`
- Modify: `snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/web/SnapAgentController.java`

- [ ] **Step 1: 写 2 个失败的测试**

在 `SnapAgentControllerSecurityTest.java` 的 `userInfoShouldNotRequireAuth` 测试之后(约第 250 行,`// ---- /user-info endpoint ----` 区块内),插入下面两个测试:

```java
    @Test
    void userInfoShouldReturnDisplayNameWhenCurrentUserNameProvided() throws Exception {
        when(securityGateway.currentUserName()).thenReturn("Alice Wang");

        mockMvc.perform(get("/snap-agent/user-info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.userId").value("user001"))
                .andExpect(jsonPath("$.username").value("Alice Wang"));
    }

    @Test
    void userInfoShouldFallbackToUserIdWhenCurrentUserNameIsNull() throws Exception {
        // currentUserName() default method on mock returns null → fallback to userId
        mockMvc.perform(get("/snap-agent/user-info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.userId").value("user001"))
                .andExpect(jsonPath("$.username").value("user001"));
    }
```

- [ ] **Step 2: 运行测试验证失败**

Run: `cd /Users/HuaSheng.Song/IdeaProjects/skills-agent && mvn -pl snap-agent-spring-boot-2x-starter test -Dtest=SnapAgentControllerSecurityTest -q`
Expected:
- `userInfoShouldReturnDisplayNameWhenCurrentUserNameProvided` 失败 — 期望 `$.username`="Alice Wang" 实际 "user001"
- `userInfoShouldFallbackToUserIdWhenCurrentUserNameIsNull` 通过(fallback 行为正好就是当前 bug 行为)

- [ ] **Step 3: 修改 `SnapAgentController.java#getUserInfo()`**

定位到第 346-347 行:

```java
        info.setUserId(userId);
        info.setUsername(userId);
```

替换为:

```java
        info.setUserId(userId);
        String displayName = securityGateway.currentUserName();
        info.setUsername(displayName != null ? displayName : userId);
```

- [ ] **Step 4: 运行测试验证通过**

Run: `cd /Users/HuaSheng.Song/IdeaProjects/skills-agent && mvn -pl snap-agent-spring-boot-2x-starter test -Dtest=SnapAgentControllerSecurityTest -q`
Expected: 全部 SnapAgentControllerSecurityTest 测试通过

- [ ] **Step 5: 提交**

```bash
cd /Users/HuaSheng.Song/IdeaProjects/skills-agent
git add snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/web/SnapAgentController.java snap-agent-spring-boot-2x-starter/src/test/java/cn/watsontech/snapagent/boot2x/web/SnapAgentControllerSecurityTest.java
git commit -m "fix(controller): populate UserInfo.username from currentUserName() with userId fallback"
```

---

## Task 4: 更新文档

**Files:**
- Modify: `docs/embeed-skills-agent/07-config-security.md`
- Modify: `docs/embeed-skills-agent/09-integration-guide.md`

- [ ] **Step 1: 在 `07-config-security.md` §2 (SecurityGateway SPI) 补充 `currentUserName()` 说明**

定位到 `## 2. SecurityGateway SPI（决策 #5）` 区块。在描述 `currentUserId()` 的段落后,插入一段说明 `currentUserName()` 的用途/默认行为/fallback 语义。具体文字:

```markdown
**`currentUserName()`** — 返回当前用户的显示名(真实姓名/昵称),用于前端右上角显示。默认实现返回 `null`,controller 会 fallback 到 userId,所以**不实现该方法时显示与改造前一致**。`SpringSecurityAdapter` 覆盖该方法调用 `Authentication#getName()`(`UserDetails` 走 `getUsername()`、`AuthenticatedPrincipal`/`Principal` 走 `getName()`、否则 `toString()`)。`ShiroAdapter` 不覆盖(Shiro 没有等价概念,宿主可自行覆盖 bean)。**注意**: `currentUserId()` 仍用于任务归属、审计、Cost 归属、限流,不要用 displayName 替代 userId。
```

- [ ] **Step 2: 在 `07-config-security.md` §3.1 (SpringSecurityAdapter) 提一句 `currentUserName()` 覆盖**

在 §3.1 区块末尾追加一句:

```markdown
`currentUserName()` 覆盖为调 `auth.getName()`,空串/null 时返回 null 让 controller fallback。
```

- [ ] **Step 3: 在 `07-config-security.md` §3.2 (ShiroAdapter) 提一句不覆盖**

在 §3.2 区块末尾追加一句:

```markdown
`currentUserName()` 不覆盖,默认返回 null,controller fallback 到 userId。需要真实姓名时由宿主覆盖 bean。
```

- [ ] **Step 4: 在 `09-integration-guide.md` §5b 加覆盖 `currentUserName()` 示例**

定位到 `## 步骤 5b：（可能需要）自定义 SecurityGateway` 区块。在该区块末尾追加新小节:

```markdown
### 5b.1 覆盖 `currentUserName()` 取真实显示名

默认 `SpringSecurityAdapter.currentUserName()` 调 `auth.getName()`,对大多数 Spring Security 宿主已够用。若你的 principal 是自定义对象且 `auth.getName()` 返回值不理想(如返回 userId 而非姓名),可覆盖:

```java
@Component
public class HostSecurityGateway extends SpringSecurityAdapter {

    public HostSecurityGateway(PrincipalResolver principalResolver) {
        super(principalResolver);
    }

    @Override
    public String currentUserName() {
        LoginUser loginUser = (LoginUser) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();
        return loginUser.getRealName();  // 或 getNickname()/getDisplayName()
    }
}
```

`@ConditionalOnMissingBean` 生效,宿主 bean 替换默认实现。`currentUserId()` 继承父类逻辑,无需重写。
```

- [ ] **Step 5: 提交**

```bash
cd /Users/HuaSheng.Song/IdeaProjects/skills-agent
git add docs/embeed-skills-agent/07-config-security.md docs/embeed-skills-agent/09-integration-guide.md
git commit -m "docs: document currentUserName() SPI and override example"
```

---

## Task 5: 全量测试 + MEMORY.md 更新

**Files:**
- Modify: `/Users/HuaSheng.Song/.claude/projects/-Users-HuaSheng-Song-IdeaProjects-skills-agent/memory/MEMORY.md`

- [ ] **Step 1: 跑 starter 模块全量测试验证零破坏**

Run: `cd /Users/HuaSheng.Song/IdeaProjects/skills-agent && mvn -pl snap-agent-spring-boot-2x-starter test -q`
Expected: 全部测试通过(基线 791 tests + 新增 5 个 = 796,扣除 2 个 pre-existing failures 与本次无关:SnapAgentControllerTest JSON path / SnapAgentControllerSecurityTest Mockito UnnecessaryStubbing;新测试不应引入新失败)

如果出现新失败,优先检查:
- `SnapAgentControllerTest` 里是否新增了 `UnnecessaryStubbingException` — 因为新加的 `when(securityGateway.currentUserName())` 在某些测试路径下没被调到,可改用 `lenient().when(...)`
- 是否有其他测试 mock 了 `SecurityGateway` 并断言 `$.username == userId` — 这种情况是宿主行为变更的正确体现,需更新断言

- [ ] **Step 2: 跑 core 模块测试验证零破坏**

Run: `cd /Users/HuaSheng.Song/IdeaProjects/skills-agent && mvn -pl snap-agent-core test -q`
Expected: 全部通过

- [ ] **Step 3: 更新 MEMORY.md**

定位到 MEMORY.md 的 `## 安全权限模型坑(2026-07-07)` 段落。在段落末尾追加一行:

```markdown
- **`SecurityGateway.currentUserName()` SPI** (2026-07-21): default 方法,返回 null 时 controller fallback 到 userId; `SpringSecurityAdapter` 覆盖调 `auth.getName()`(空串/null 视为 null); `ShiroAdapter` 不覆盖; `SnapAgentController.getUserInfo()` 用 `info.setUsername(displayName != null ? displayName : userId)` 修 bug(原本 `setUsername(userId)`)
```

- [ ] **Step 4: 提交**

```bash
cd /Users/HuaSheng.Song/IdeaProjects/skills-agent
git add /Users/HuaSheng.Song/.claude/projects/-Users-HuaSheng-Song-IdeaProjects-skills-agent/memory/MEMORY.md
git commit -m "docs(memory): record currentUserName() SPI addition"
```

注: MEMORY.md 在 `~/.claude/projects/` 下,可能不在 git 仓库 tracked 范围;若 `git status` 显示该路径未 tracked 或被 .gitignore 忽略,跳过 commit,仅本地保存即可。

---

## 完成验收

- [ ] `SecurityGateway.currentUserName()` default 方法存在
- [ ] `SpringSecurityAdapter.currentUserName()` 覆盖为 `auth.getName()` + null/空串保护
- [ ] `SnapAgentController.getUserInfo()` 使用 `currentUserName()`,null fallback userId
- [ ] `SpringSecurityAdapterTest` 新增 3 个用例全绿
- [ ] `SnapAgentControllerSecurityTest` 新增 2 个用例全绿
- [ ] starter 模块全量测试除 2 个 pre-existing failures 外全绿
- [ ] docs 07/09 已更新
- [ ] MEMORY.md 已记录
- [ ] 所有改动已 commit
