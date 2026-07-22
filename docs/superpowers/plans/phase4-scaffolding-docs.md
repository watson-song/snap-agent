# Phase 4: Scaffolding & Docs — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create a Maven archetype module that generates SnapAgent custom plugin project scaffolds, plus developer documentation (Chinese + English) covering the full plugin development lifecycle.

**Architecture:** The archetype is a standalone Maven module (`snap-agent-plugin-archetype`, packaging `maven-archetype`) containing template files with `${property}` substitution. The generated project depends on `snap-agent-core` with `provided` scope. Documentation lives under `docs/site/plugins/{zh,en}/`.

**Tech Stack:** Java 8, Maven Archetype Plugin 3.2.1, Spring Boot 2.5.15 (parent of generated project), JUnit 5, AssertJ.

**Prerequisites:** Phases 1-3 must be complete — `PluginDescriptor`, `PluginRegistry`, `PluginContext`, `ToolContext` (with pluginOverrides), `ToolDispatcher` (refactored), `@ToolPluginAnnotation`, `PluginUploader`, REST API endpoints, and autoconfig wrapping all exist in the codebase.

参考 spec: `docs/superpowers/specs/2026-07-21-plugin-architecture-refactor-design.md` (sections 5.1, 5.2, 5.3, 6.1-6.3)

---

## 文件清单

| 文件 | 操作 | 责任 |
|------|------|------|
| `snap-agent-plugin-archetype/pom.xml` | 新建 | Archetype 模块 POM (packaging: maven-archetype) |
| `snap-agent-plugin-archetype/src/main/resources/META-INF/maven/archetype-metadata.xml` | 新建 | Archetype 元数据: requiredProperties + fileSets |
| `snap-agent-plugin-archetype/src/main/resources/archetype-resources/pom.xml` | 新建 | 生成项目的 POM 模板 (depends on snap-agent-core provided) |
| `snap-agent-plugin-archetype/src/main/resources/archetype-resources/src/main/java/__className__.java` | 新建 | ToolProvider 实现模板 (@ToolPluginAnnotation) |
| `snap-agent-plugin-archetype/src/main/resources/archetype-resources/src/main/resources/META-INF/snap-agent/plugin-info.yml` | 新建 | 备用清单模板 |
| `snap-agent-plugin-archetype/src/main/resources/archetype-resources/src/test/java/__className__Test.java` | 新建 | TDD 测试骨架模板 |
| `pom.xml` | 修改 | 父 POM `<modules>` 加 `snap-agent-plugin-archetype` |
| `docs/site/plugins/zh/plugin-development-guide.md` | 新建 | 中文开发指南 (9 节) |
| `docs/site/plugins/en/plugin-development-guide.md` | 新建 | 英文开发指南 (9 节) |
| `docs/site/plugins/zh/tool-plugin-architecture.md` | 修改 | 追加 §9 v0.5 Plugin 架构 |
| `docs/site/plugins/en/tool-plugin-architecture.md` | 修改 | 追加 §9 v0.5 Plugin Architecture |

---

## Task 10: Maven Archetype Scaffold + Example Plugin

**Files:**
- Create: `snap-agent-plugin-archetype/pom.xml`
- Create: `snap-agent-plugin-archetype/src/main/resources/META-INF/maven/archetype-metadata.xml`
- Create: `snap-agent-plugin-archetype/src/main/resources/archetype-resources/pom.xml`
- Create: `snap-agent-plugin-archetype/src/main/resources/archetype-resources/src/main/java/__className__.java`
- Create: `snap-agent-plugin-archetype/src/main/resources/archetype-resources/src/main/resources/META-INF/snap-agent/plugin-info.yml`
- Create: `snap-agent-plugin-archetype/src/main/resources/archetype-resources/src/test/java/__className__Test.java`
- Modify: `pom.xml` (parent)

- [ ] **Step 1: 创建 archetype 模块目录结构**

Run:
```bash
cd /Users/HuaSheng.Song/IdeaProjects/skills-agent/.worktrees/feat-v0.5-plugin-improvement
mkdir -p snap-agent-plugin-archetype/src/main/resources/META-INF/maven
mkdir -p snap-agent-plugin-archetype/src/main/resources/archetype-resources/src/main/java
mkdir -p snap-agent-plugin-archetype/src/main/resources/archetype-resources/src/main/resources/META-INF/snap-agent
mkdir -p snap-agent-plugin-archetype/src/main/resources/archetype-resources/src/test/java
```

Expected: 所有目录创建成功，无报错。用 `ls -R snap-agent-plugin-archetype/` 验证目录树。

- [ ] **Step 2: 创建 archetype 模块 POM**

Create file: `snap-agent-plugin-archetype/pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>cn.watsontech.snapagent</groupId>
        <artifactId>snap-agent-parent</artifactId>
        <version>0.4.0-feat-v0.5-plugin-improvement-SNAPSHOT</version>
    </parent>

    <artifactId>snap-agent-plugin-archetype</artifactId>
    <packaging>maven-archetype</packaging>

    <name>SnapAgent Plugin Archetype</name>
    <description>Maven archetype for generating SnapAgent custom plugin project scaffolds</description>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-archetype-plugin</artifactId>
                <version>3.2.1</version>
                <extensions>true</extensions>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 3: 创建 archetype-metadata.xml**

Create file: `snap-agent-plugin-archetype/src/main/resources/META-INF/maven/archetype-metadata.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<archetype xmlns="http://maven.apache.org/plugins/maven-archetype-plugin/archetype/2.0.0"
           xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
           xsi:schemaLocation="http://maven.apache.org/plugins/maven-archetype-plugin/archetype/2.0.0
                               http://maven.apache.org/plugins/maven-archetype-plugin/archetype/2.0.0/archetype-metadata.xsd">
    <id>snap-agent-plugin-archetype</id>

    <requiredProperties>
        <requiredProperty key="groupId"/>
        <requiredProperty key="artifactId"/>
        <requiredProperty key="version">
            <defaultValue>1.0.0-SNAPSHOT</defaultValue>
        </requiredProperty>
        <requiredProperty key="package">
            <defaultValue>${groupId}</defaultValue>
        </requiredProperty>
        <requiredProperty key="pluginId">
            <defaultValue>${artifactId}</defaultValue>
        </requiredProperty>
        <requiredProperty key="toolType"/>
        <requiredProperty key="displayName"/>
        <requiredProperty key="description"/>
        <requiredProperty key="className"/>
        <requiredProperty key="snapAgentVersion">
            <defaultValue>0.4.0-SNAPSHOT</defaultValue>
        </requiredProperty>
    </requiredProperties>

    <fileSets>
        <fileSet filtered="true" packaged="true">
            <directory>src/main/java</directory>
            <includes>
                <include>**/*.java</include>
            </includes>
        </fileSet>
        <fileSet filtered="true">
            <directory>src/main/resources</directory>
            <includes>
                <include>**/*</include>
            </includes>
        </fileSet>
        <fileSet filtered="true" packaged="true">
            <directory>src/test/java</directory>
            <includes>
                <include>**/*.java</include>
            </includes>
        </fileSet>
    </fileSets>
</archetype>
```

**说明:**
- `packaged="true"` 让 `src/main/java` 和 `src/test/java` 下的文件自动放到 `${package}` 目录下。
- `filtered="true"` 启用 `${property}` 内容替换。
- `snapAgentVersion` 默认 `0.4.0-SNAPSHOT`，用户生成时可覆盖为实际发布版本。
- `pluginId` 默认取 `artifactId`，用户可覆盖为更短的标识符。

- [ ] **Step 4: 创建模板 pom.xml (archetype-resources)**

Create file: `snap-agent-plugin-archetype/src/main/resources/archetype-resources/pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>2.5.15</version>
        <relativePath/>
    </parent>

    <groupId>${groupId}</groupId>
    <artifactId>${artifactId}</artifactId>
    <version>${version}</version>
    <packaging>jar</packaging>

    <name>${displayName}</name>
    <description>${description}</description>

    <properties>
        <java.version>1.8</java.version>
    </properties>

    <dependencies>
        <!-- SnapAgent Core SPI (provided by the host application at runtime) -->
        <dependency>
            <groupId>cn.watsontech.snapagent</groupId>
            <artifactId>snap-agent-core</artifactId>
            <version>${snapAgentVersion}</version>
            <scope>provided</scope>
        </dependency>

        <!-- Spring Boot (provided by the host application) -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter</artifactId>
            <scope>provided</scope>
            <optional>true</optional>
        </dependency>

        <!-- Test -->
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.assertj</groupId>
            <artifactId>assertj-core</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

**说明:**
- 生成项目继承 `spring-boot-starter-parent` (2.5.15)，管理 JUnit/AssertJ 等测试依赖版本。
- `snap-agent-core` 用 `provided` scope —— 运行时由宿主应用提供，打包时不打入 plugin JAR。
- `spring-boot-starter` 也用 `provided + optional` —— 宿主已有。
- `${snapAgentVersion}` 在生成时替换为实际版本号 (如 `0.4.0-SNAPSHOT`)。
- 模板中除 archetype 属性外不使用 `${...}`，避免 Maven 属性与 archetype 替换冲突。

- [ ] **Step 5: 创建模板 Java 文件**

Create file: `snap-agent-plugin-archetype/src/main/resources/archetype-resources/src/main/java/__className__.java`

> 文件名使用 `__className__` 约定 (Maven archetype 文件名替换语法)。生成时 `__className__` 被替换为属性值 (如 `RemoteLogToolProvider`)，`packaged="true"` 使文件自动放到 `${package}` 目录下。

```java
package ${package};

import cn.watsontech.snapagent.core.tool.ToolContext;
import cn.watsontech.snapagent.core.tool.ToolPluginAnnotation;
import cn.watsontech.snapagent.core.tool.ToolProvider;
import cn.watsontech.snapagent.core.tool.ToolResult;

import java.util.Map;

/**
 * ${displayName} — ${description}
 *
 * <p>Generated by snap-agent-plugin-archetype. Replace the execute() body
 * with your plugin's actual logic.</p>
 */
@ToolPluginAnnotation(
    id = "${pluginId}",
    toolType = "${toolType}",
    displayName = "${displayName}",
    description = "${description}",
    version = "1.0.0"
)
public class ${className} implements ToolProvider {

    @Override
    public String name() {
        return "${pluginId}";
    }

    @Override
    public String schema() {
        return "{\"name\":\"${toolType}\","
             + "\"description\":\"${description}\","
             + "\"input_schema\":{\"type\":\"object\","
             + "\"properties\":{\"query\":{\"type\":\"string\","
             + "\"description\":\"The query string to pass to the tool\"}},"
             + "\"required\":[\"query\"]}}";
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
        long start = System.currentTimeMillis();
        try {
            String query = args != null ? (String) args.get("query") : null;
            if (query == null || query.isEmpty()) {
                return ToolResult.error("missing required parameter: query",
                                         System.currentTimeMillis() - start);
            }
            String content = "# ${displayName}\n\nQuery: " + query;
            return ToolResult.success(content, 1, System.currentTimeMillis() - start);
        } catch (RuntimeException e) {
            return ToolResult.error("${pluginId} failed: " + e.getMessage(),
                                     System.currentTimeMillis() - start);
        }
    }
}
```

**说明:**
- `@ToolPluginAnnotation` 是 Phase 2 在 `snap-agent-core` 中创建的注解 (包 `cn.watsontech.snapagent.core.tool`)。如果 Phase 2 将其放在不同包，调整 import。
- `name()` 返回 `pluginId` (与 `@ToolPluginAnnotation.id()` 一致)。
- `schema()` 返回 Anthropic 工具格式的 JSON，`name` 字段 = `toolType`。
- `execute()` 处理 null args → error，valid query → success。无 TODO 占位符。

- [ ] **Step 6: 创建模板 plugin-info.yml**

Create file: `snap-agent-plugin-archetype/src/main/resources/archetype-resources/src/main/resources/META-INF/snap-agent/plugin-info.yml`

```yaml
id: ${pluginId}
toolType: ${toolType}
displayName: "${displayName}"
description: "${description}"
version: 1.0.0
isDefault: false
providerClass: ${package}.${className}
```

**说明:**
- 备用清单 —— 当 JAR 中没有 `@ToolPluginAnnotation` 注解时，`PluginUploader` 从此文件读取元数据。
- `displayName` 和 `description` 加引号，防止 YAML 特殊字符导致解析失败。
- `providerClass` = `${package}.${className}` (生成时替换为如 `com.example.RemoteLogToolProvider`)。

- [ ] **Step 7: 创建模板测试文件**

Create file: `snap-agent-plugin-archetype/src/main/resources/archetype-resources/src/test/java/__className__Test.java`

```java
package ${package};

import cn.watsontech.snapagent.core.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ${className}Test {

    private ${className} provider;

    @BeforeEach
    void setUp() {
        provider = new ${className}();
    }

    @Test
    void nameReturnsPluginId() {
        assertThat(provider.name()).isEqualTo("${pluginId}");
    }

    @Test
    void schemaContainsToolType() {
        String schema = provider.schema();
        assertThat(schema).isNotEmpty();
        assertThat(schema).contains("\"name\"");
        assertThat(schema).contains("\"${toolType}\"");
    }

    @Test
    void executeWithNullArgsReturnsError() {
        ToolResult result = provider.execute(null, null);
        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).isNull();
    }

    @Test
    void executeWithValidQueryReturnsSuccess() {
        Map<String, Object> args = new HashMap<>();
        args.put("query", "test query");
        ToolResult result = provider.execute(args, null);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("test query");
    }
}
```

**说明:**
- 使用 JUnit 5 (`@BeforeEach`, `@Test` from `org.junit.jupiter.api`)。
- 4 个测试覆盖: name() 返回 pluginId、schema() 包含 toolType、null args → error、valid query → success。
- 无 TODO 或占位符 —— 测试完整可运行。

- [ ] **Step 8: 将 archetype 模块添加到父 POM**

Modify: `pom.xml` (parent，根目录)

在 `<modules>` 中，`<module>snap-agent-anchor-demo</module>` 之后添加 `<module>snap-agent-plugin-archetype</module>`。

修改后的 `<modules>` 段:

```xml
    <modules>
        <module>snap-agent-core</module>
        <module>snap-agent-spring-boot-2x-starter</module>
        <module>snap-agent-client</module>
        <module>snap-agent-anchor-demo</module>
        <module>snap-agent-plugin-archetype</module>
    </modules>
```

- [ ] **Step 9: 安装 archetype 并验证构建**

Run:
```bash
cd /Users/HuaSheng.Song/IdeaProjects/skills-agent/.worktrees/feat-v0.5-plugin-improvement
mvn install -pl snap-agent-plugin-archetype -N -q
```

Expected: BUILD SUCCESS。archetype JAR 安装到本地 Maven 仓库。

如果报错 `Could not resolve snap-agent-parent`，先运行 `mvn install -N` (安装父 POM)。

- [ ] **Step 10: 验证 archetype 生成 (手动测试)**

> 此步骤为验证步骤 —— 在终端中执行，确认生成的项目结构正确。

Run:
```bash
cd /tmp
mvn archetype:generate \
  -DarchetypeGroupId=cn.watsontech.snapagent \
  -DarchetypeArtifactId=snap-agent-plugin-archetype \
  -DarchetypeVersion=0.4.0-feat-v0.5-plugin-improvement-SNAPSHOT \
  -DgroupId=com.example \
  -DartifactId=my-remote-log-plugin \
  -Dversion=1.0.0-SNAPSHOT \
  -Dpackage=com.example \
  -DpluginId=remote-log \
  -DtoolType=log_read \
  -DdisplayName=RemoteLogQuery \
  -Ddescription="Query remote Loki logs" \
  -DclassName=RemoteLogToolProvider \
  -DsnapAgentVersion=0.4.0-SNAPSHOT \
  -DinteractiveMode=false
```

Expected:
- 生成 `/tmp/my-remote-log-plugin/` 目录。
- 验证生成的文件结构:
  ```bash
  ls -R /tmp/my-remote-log-plugin/
  ```
- 应包含:
  - `pom.xml` (groupId=com.example, artifactId=my-remote-log-plugin, depends on snap-agent-core 0.4.0-SNAPSHOT provided)
  - `src/main/java/com/example/RemoteLogToolProvider.java` (带 @ToolPluginAnnotation 注解)
  - `src/main/resources/META-INF/snap-agent/plugin-info.yml` (id: remote-log, providerClass: com.example.RemoteLogToolProvider)
  - `src/test/java/com/example/RemoteLogToolProviderTest.java` (4 个测试)

验证生成的 Java 文件内容:
```bash
cat /tmp/my-remote-log-plugin/src/main/java/com/example/RemoteLogToolProvider.java
```
- 确认 `@ToolPluginAnnotation(id = "remote-log", toolType = "log_read", ...)` 所有 `${property}` 已替换。
- 确认 `package com.example;`。

验证生成的 plugin-info.yml:
```bash
cat /tmp/my-remote-log-plugin/src/main/resources/META-INF/snap-agent/plugin-info.yml
```
- 确认 `id: remote-log`、`providerClass: com.example.RemoteLogToolProvider`。

验证生成的项目可编译:
```bash
cd /tmp/my-remote-log-plugin && mvn compile -q
```
Expected: BUILD SUCCESS (需本地 Maven 仓库已安装 snap-agent-core 0.4.0-SNAPSHOT)。

清理:
```bash
rm -rf /tmp/my-remote-log-plugin
```

- [ ] **Step 11: 提交**

```bash
cd /Users/HuaSheng.Song/IdeaProjects/skills-agent/.worktrees/feat-v0.5-plugin-improvement
git add snap-agent-plugin-archetype/ pom.xml
git commit -m "feat: add snap-agent-plugin-archetype Maven archetype scaffold

New module generates custom plugin project scaffolds via:
  mvn archetype:generate -DarchetypeGroupId=cn.watsontech.snapagent \
    -DarchetypeArtifactId=snap-agent-plugin-archetype ...

Generated project includes @ToolPluginAnnotation template, ToolProvider
implementation, plugin-info.yml backup manifest, and JUnit 5 test skeleton."
```

---

## Task 11: Developer Documentation

### Task 11a: 中文开发指南

**Files:**
- Create: `docs/site/plugins/zh/plugin-development-guide.md`

- [ ] **Step 1: 创建中文开发指南**

Create file: `docs/site/plugins/zh/plugin-development-guide.md`

```markdown
# SnapAgent 自定义 Plugin 开发指南

> 版本：v0.5 | 更新日期：2026-07-22

本指南介绍如何为 SnapAgent 开发自定义 Plugin —— 从项目生成到打包、上传、配置和测试的完整流程。

---

## 1. 核心概念

### Plugin = 1 个 ToolProvider + 元数据声明

SnapAgent 的 Plugin 是一个可热插拔的工具单元。每个 Plugin 包含:

- **1 个 `ToolProvider` 实现** —— 提供 `name()`、`schema()`、`execute()` 三个方法
- **元数据声明** —— 通过 `@ToolPluginAnnotation` 注解 (优先) 或 `plugin-info.yml` (备用) 声明

### toolType vs pluginId

| 概念 | 说明 | 示例 |
|------|------|------|
| `toolType` | LLM 调用时看到的工具名。一个 toolType 可有多个 Plugin | `log_read` |
| `pluginId` | Plugin 的唯一标识 | `remote-log`、`local-log` |

LLM 不感知 plugin 的存在 —— 它只看到 `toolType`。`ToolDispatcher` 按 `pluginOverrides` 或默认 Plugin 路由到具体实现。

### 1 plugin = 1 tool

一个 Plugin 对应一个 LLM 可调用的工具。Tool 内部可有多个 operation（通过 schema 参数分支），例如 `mysql_query` 可支持 `query`、`explain`、`slow-log` 三种操作。

### system plugin vs custom plugin

| 类型 | 来源 | 可删除 |
|------|------|--------|
| system plugin | 内置 `@Component ToolProvider` bean，启动时自动包装 | 否 |
| custom plugin | 通过 `POST /tools/plugins/upload` 上传的 JAR | 是 |

向后兼容: 现有 `@Component ToolProvider` bean 无需修改，启动时自动包装为 system plugin。

---

## 2. 快速开始

### 2.1 用 Maven Archetype 生成项目

```bash
mvn archetype:generate \
  -DarchetypeGroupId=cn.watsontech.snapagent \
  -DarchetypeArtifactId=snap-agent-plugin-archetype \
  -DarchetypeVersion=0.4.0-SNAPSHOT \
  -DgroupId=com.example \
  -DartifactId=my-remote-log-plugin \
  -Dversion=1.0.0-SNAPSHOT \
  -Dpackage=com.example \
  -DpluginId=remote-log \
  -DtoolType=log_read \
  -DdisplayName=远程日志查询 \
  -Ddescription="查询 Loki 历史日志" \
  -DclassName=RemoteLogToolProvider \
  -DinteractiveMode=false
```

生成项目结构:

```
my-remote-log-plugin/
├── pom.xml                                    ← 依赖 snap-agent-core (provided)
├── src/main/java/com/example/
│   └── RemoteLogToolProvider.java             ← @ToolPluginAnnotation + ToolProvider
├── src/main/resources/META-INF/snap-agent/
│   └── plugin-info.yml                        ← 备用清单
└── src/test/java/com/example/
    └── RemoteLogToolProviderTest.java          ← 测试骨架
```

### 2.2 实现 ToolProvider

生成的 `RemoteLogToolProvider.java` 已包含基本骨架。核心是三个方法:

```java
@ToolPluginAnnotation(
    id = "remote-log",
    toolType = "log_read",
    displayName = "远程日志查询",
    description = "查询 Loki 历史日志",
    version = "1.0.0"
)
public class RemoteLogToolProvider implements ToolProvider {

    @Override
    public String name() {
        return "remote-log";  // 必须与 @ToolPluginAnnotation.id() 一致
    }

    @Override
    public String schema() {
        // Anthropic 工具格式 JSON，name 字段 = toolType
        return "{\"name\":\"log_read\", ... }";
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
        // 你的业务逻辑
        String query = (String) args.get("query");
        // ... 调用 Loki API ...
        return ToolResult.success(content, rowCount, durationMs);
    }
}
```

### 2.3 Operation 多功能分支

一个 tool 内可通过 schema 参数支持多种操作。以 `mysql_query` 为例:

```java
@Override
public String schema() {
    return "{\"name\":\"mysql_query\","
         + "\"input_schema\":{\"type\":\"object\","
         + "\"properties\":{"
         +   "\"operation\":{\"type\":\"string\",\"enum\":[\"query\",\"explain\",\"slow-log\"]},"
         +   "\"sql\":{\"type\":\"string\"}"
         + "},\"required\":[\"operation\"]}}";
}

@Override
public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
    String operation = (String) args.get("operation");
    switch (operation) {
        case "query":     return executeQuery(args, ctx);
        case "explain":   return executeExplain(args, ctx);
        case "slow-log":  return executeSlowLog(args, ctx);
        default:          return ToolResult.error("unknown operation: " + operation, 0);
    }
}
```

---

## 3. 打包

```bash
cd my-remote-log-plugin
mvn clean package
```

打包要点:

- `snap-agent-core` 使用 `provided` scope —— **不打入** plugin JAR (运行时由宿主提供)
- `spring-boot-starter` 也是 `provided + optional` —— 不打入
- 生成的 JAR 是 skinny jar (仅含你的代码 + plugin-info.yml)，体积小
- 不要打成 fat jar —— 宿主已有 Spring/Jackson 等依赖，重复打入会导致 ClassLoader 冲突

输出: `target/my-remote-log-plugin-1.0.0-SNAPSHOT.jar`

---

## 4. 上传

```bash
curl -X POST http://localhost:8080/skills-agent/tools/plugins/upload \
  -H "Authorization: Basic $(echo -n 'admin:password' | base64)" \
  -F "jar=@target/my-remote-log-plugin-1.0.0-SNAPSHOT.jar"
```

响应示例:

```json
{
    "pluginId": "remote-log",
    "toolType": "log_read",
    "displayName": "远程日志查询",
    "description": "查询 Loki 历史日志",
    "version": "1.0.0",
    "isDefault": false,
    "enabled": true,
    "system": false,
    "jarPath": "/data/snap-agent/plugins/remote-log/plugin.jar"
}
```

上传流程:
1. JAR 保存到 `${upload-skills-dir}/plugins/{pluginId}/plugin.jar`
2. 创建 `URLClassLoader` (parent = 主应用 ClassLoader)
3. 扫描元数据 (`@ToolPluginAnnotation` 优先，`plugin-info.yml` 兜底)
4. 实例化 `ToolProvider` (要求无参构造)
5. 构造 `PluginDescriptor` + 注册到 `PluginRegistry`
6. 默认 `isDefault=false` —— 需主动调用 `PUT /tools/plugins/{id}/default` 设为默认

---

## 5. 配置

### YAML 配置

```yaml
snap-agent:
  tools:
    remote-log:
      base-url: http://loki:3100
      timeout-seconds: 30
      max-lines: 500
```

### 在 Plugin 中读取配置

```java
@Override
public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
    // ctx.getPluginContext() 在 custom plugin 执行时由 ToolDispatcher 注入
    // system plugin 的 pluginContext 为 null (它们通过 Spring @Value 注入)
    if (ctx.getPluginContext() != null) {
        Map<String, Object> config = ctx.getPluginContext().getConfiguration();
        String baseUrl = (String) config.get("base-url");
        int timeout = (Integer) config.getOrDefault("timeout-seconds", 30);
        // 使用配置...
    }
    // ...
}
```

配置来源: `snap-agent.tools.{pluginId}.*` 命名空间下的所有属性。

---

## 6. 禁用 / 启用 / 设默认 / 反注册

```bash
# 禁用 plugin (从 LLM 工具列表中移除，但保留在 registry)
curl -X POST http://localhost:8080/skills-agent/tools/plugins/remote-log/disable \
  -H "Authorization: Basic ..."

# 启用 plugin
curl -X POST http://localhost:8080/skills-agent/tools/plugins/remote-log/enable \
  -H "Authorization: Basic ..."

# 设为该 toolType 的默认 plugin
curl -X PUT http://localhost:8080/skills-agent/tools/plugins/remote-log/default \
  -H "Authorization: Basic ..."

# 反注册 (system plugin 返回 403)
curl -X DELETE http://localhost:8080/skills-agent/tools/plugins/remote-log \
  -H "Authorization: Basic ..."
```

| 操作 | 端点 | 权限 |
|------|------|------|
| 禁用 | `POST /tools/plugins/{id}/disable` | `snap-agent:plugin:manage` |
| 启用 | `POST /tools/plugins/{id}/enable` | `snap-agent:plugin:manage` |
| 设默认 | `PUT /tools/plugins/{id}/default` | `snap-agent:plugin:manage` |
| 反注册 | `DELETE /tools/plugins/{id}` | `snap-agent:plugin:manage` |

> 生产环境推荐用 **disable** 而非 unregister —— ClassLoader GC 不保证立即回收。

---

## 7. 在 Skill 中使用 Plugin

### 通过 pluginOverrides 指定 plugin

```bash
curl -X POST http://localhost:8080/skills-agent/runs \
  -H "Authorization: Basic ..." \
  -H "Content-Type: application/json" \
  -d '{
    "skillName": "log-analysis",
    "inputs": {"keyword": "timeout"},
    "pluginOverrides": {
      "log_read": "remote-log"
    }
  }'
```

### 工作原理

```
LLM → tool_use(log_read, args)
    → ToolDispatcher.dispatch("log_read", args, ctx)
    → ctx.pluginOverrides["log_read"] = "remote-log"
    → registry.getPlugin("remote-log")
    → plugin.provider.execute(args, ctx)
    → ToolResult
```

- LLM 只看到 `toolType` (如 `log_read`)，不感知 plugin
- `ToolDispatcher` 先查 `ctx.pluginOverrides[toolType]`，再查 `registry.getDefault(toolType)`
- 不传 `pluginOverrides` 时走默认 plugin —— 向后兼容

---

## 8. 测试 Plugin

### 单元测试模板

生成的测试骨架已包含 4 个测试:

```java
class RemoteLogToolProviderTest {

    private RemoteLogToolProvider provider;

    @BeforeEach
    void setUp() {
        provider = new RemoteLogToolProvider();
    }

    @Test
    void nameReturnsPluginId() {
        assertThat(provider.name()).isEqualTo("remote-log");
    }

    @Test
    void schemaContainsToolType() {
        assertThat(provider.schema()).contains("\"log_read\"");
    }

    @Test
    void executeWithNullArgsReturnsError() {
        ToolResult result = provider.execute(null, null);
        assertThat(result.isError()).isTrue();
    }

    @Test
    void executeWithValidQueryReturnsSuccess() {
        Map<String, Object> args = new HashMap<>();
        args.put("query", "test");
        ToolResult result = provider.execute(args, null);
        assertThat(result.isSuccess()).isTrue();
    }
}
```

### 本地验证流程

```bash
# 1. 编译 + 运行测试
cd my-remote-log-plugin
mvn clean test

# 2. 打包
mvn package

# 3. 上传到本地 SnapAgent 实例
curl -X POST http://localhost:8080/skills-agent/tools/plugins/upload \
  -F "jar=@target/my-remote-log-plugin-1.0.0-SNAPSHOT.jar"

# 4. 验证已注册
curl http://localhost:8080/skills-agent/tools/plugins

# 5. 执行 skill 验证
curl -X POST http://localhost:8080/skills-agent/runs \
  -H "Content-Type: application/json" \
  -d '{"skillName":"log-analysis","inputs":{"keyword":"test"},"pluginOverrides":{"log_read":"remote-log"}}'
```

---

## 9. 限制与最佳实践

### ClassLoader 卸载

Java ClassLoader GC **不保证立即回收**。反注册 plugin 后，其 `URLClassLoader` 被关闭并等待 GC，但若有静态状态、线程或未关闭资源，可能泄漏。

- **推荐**: 生产环境用 `disable` 而非 `unregister`
- **监控**: 关注 ClassLoader 数量，异常增长时排查

### ThreadLocal

Plugin 在独立的 `URLClassLoader` 中执行，**不能访问主应用的 ThreadLocal**。所有请求级信息 (userId、taskId) 必须从 `ToolContext` 获取:

```java
@Override
public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
    String userId = ctx.getUserId();   // 正确
    // String userId = (String) ThreadLocalContext.get();  // 错误 — 不可用
}
```

### ToolResult.content 必须是 String

`ToolResult.getContent()` 返回 `String` 类型，确保跨 ClassLoader 安全。不要尝试返回自定义对象。

### 线程

不要在 Plugin 内启动**非 daemon 线程**。非 daemon 线程会阻止 JVM 退出。

### 依赖兼容

Plugin 可访问宿主的 Spring Bean (如 `DataSource`)。需确保宿主 Bean 版本与 Plugin 编译时依赖兼容。`snap-agent-core` API 稳定版保证向后兼容。
```

- [ ] **Step 2: 验证中文指南内容**

Run:
```bash
cd /Users/HuaSheng.Song/IdeaProjects/skills-agent/.worktrees/feat-v0.5-plugin-improvement
grep -c '^## ' docs/site/plugins/zh/plugin-development-guide.md
```

Expected: `9` (9 个一级章节: 1-9)

验证所有 9 节标题存在:
```bash
grep '^## ' docs/site/plugins/zh/plugin-development-guide.md
```

Expected 输出包含:
```
## 1. 核心概念
## 2. 快速开始
## 3. 打包
## 4. 上传
## 5. 配置
## 6. 禁用 / 启用 / 设默认 / 反注册
## 7. 在 Skill 中使用 Plugin
## 8. 测试 Plugin
## 9. 限制与最佳实践
```

- [ ] **Step 3: 提交中文指南**

```bash
cd /Users/HuaSheng.Song/IdeaProjects/skills-agent/.worktrees/feat-v0.5-plugin-improvement
git add docs/site/plugins/zh/plugin-development-guide.md
git commit -m "docs: add Chinese plugin development guide (9 sections)"
```

---

### Task 11b: 英文开发指南

**Files:**
- Create: `docs/site/plugins/en/plugin-development-guide.md`

- [ ] **Step 1: 创建英文开发指南**

Create file: `docs/site/plugins/en/plugin-development-guide.md`

```markdown
# SnapAgent Custom Plugin Development Guide

> Version: v0.5 | Updated: 2026-07-22

This guide covers the full lifecycle of developing a custom Plugin for SnapAgent — from project generation through packaging, upload, configuration, and testing.

---

## 1. Core Concepts

### Plugin = 1 ToolProvider + Metadata Declaration

A SnapAgent Plugin is a hot-pluggable tool unit. Each Plugin contains:

- **1 `ToolProvider` implementation** — provides `name()`, `schema()`, `execute()`
- **Metadata declaration** — via `@ToolPluginAnnotation` annotation (preferred) or `plugin-info.yml` (fallback)

### toolType vs pluginId

| Concept | Description | Example |
|---------|-------------|---------|
| `toolType` | Tool name the LLM sees when calling. One toolType can have multiple Plugins | `log_read` |
| `pluginId` | Unique identifier for the Plugin | `remote-log`, `local-log` |

The LLM does not perceive plugins — it only sees `toolType`. `ToolDispatcher` routes to the specific implementation via `pluginOverrides` or the default Plugin.

### 1 plugin = 1 tool

One Plugin corresponds to one LMS-callable tool. A tool can have multiple operations (via schema parameter branching), e.g., `mysql_query` can support `query`, `explain`, and `slow-log` operations.

### system plugin vs custom plugin

| Type | Source | Removable |
|------|--------|-----------|
| system plugin | Built-in `@Component ToolProvider` beans, auto-wrapped at startup | No |
| custom plugin | JAR uploaded via `POST /tools/plugins/upload` | Yes |

Backward compatibility: existing `@Component ToolProvider` beans require no modification — auto-wrapped as system plugins at startup.

---

## 2. Quick Start

### 2.1 Generate a Project with Maven Archetype

```bash
mvn archetype:generate \
  -DarchetypeGroupId=cn.watsontech.snapagent \
  -DarchetypeArtifactId=snap-agent-plugin-archetype \
  -DarchetypeVersion=0.4.0-SNAPSHOT \
  -DgroupId=com.example \
  -DartifactId=my-remote-log-plugin \
  -Dversion=1.0.0-SNAPSHOT \
  -Dpackage=com.example \
  -DpluginId=remote-log \
  -DtoolType=log_read \
  -DdisplayName=RemoteLogQuery \
  -Ddescription="Query remote Loki logs" \
  -DclassName=RemoteLogToolProvider \
  -DinteractiveMode=false
```

Generated project structure:

```
my-remote-log-plugin/
├── pom.xml                                    ← depends on snap-agent-core (provided)
├── src/main/java/com/example/
│   └── RemoteLogToolProvider.java             ← @ToolPluginAnnotation + ToolProvider
├── src/main/resources/META-INF/snap-agent/
│   └── plugin-info.yml                        ← backup manifest
└── src/test/java/com/example/
    └── RemoteLogToolProviderTest.java          ← test skeleton
```

### 2.2 Implement ToolProvider

The generated `RemoteLogToolProvider.java` contains a basic skeleton. The core is three methods:

```java
@ToolPluginAnnotation(
    id = "remote-log",
    toolType = "log_read",
    displayName = "RemoteLogQuery",
    description = "Query remote Loki logs",
    version = "1.0.0"
)
public class RemoteLogToolProvider implements ToolProvider {

    @Override
    public String name() {
        return "remote-log";  // must match @ToolPluginAnnotation.id()
    }

    @Override
    public String schema() {
        // Anthropic tool format JSON, name field = toolType
        return "{\"name\":\"log_read\", ... }";
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
        // your business logic
        String query = (String) args.get("query");
        // ... call Loki API ...
        return ToolResult.success(content, rowCount, durationMs);
    }
}
```

### 2.3 Multi-Operation Branching

A tool can support multiple operations via schema parameters. Example with `mysql_query`:

```java
@Override
public String schema() {
    return "{\"name\":\"mysql_query\","
         + "\"input_schema\":{\"type\":\"object\","
         + "\"properties\":{"
         +   "\"operation\":{\"type\":\"string\",\"enum\":[\"query\",\"explain\",\"slow-log\"]},"
         +   "\"sql\":{\"type\":\"string\"}"
         + "},\"required\":[\"operation\"]}}";
}

@Override
public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
    String operation = (String) args.get("operation");
    switch (operation) {
        case "query":     return executeQuery(args, ctx);
        case "explain":   return executeExplain(args, ctx);
        case "slow-log":  return executeSlowLog(args, ctx);
        default:          return ToolResult.error("unknown operation: " + operation, 0);
    }
}
```

---

## 3. Packaging

```bash
cd my-remote-log-plugin
mvn clean package
```

Packaging notes:

- `snap-agent-core` uses `provided` scope — **not included** in the plugin JAR (provided by host at runtime)
- `spring-boot-starter` is also `provided + optional` — not included
- The output is a skinny JAR (only your code + plugin-info.yml), small in size
- Do NOT build a fat JAR — the host already has Spring/Jackson etc., duplicating them causes ClassLoader conflicts

Output: `target/my-remote-log-plugin-1.0.0-SNAPSHOT.jar`

---

## 4. Upload

```bash
curl -X POST http://localhost:8080/skills-agent/tools/plugins/upload \
  -H "Authorization: Basic $(echo -n 'admin:password' | base64)" \
  -F "jar=@target/my-remote-log-plugin-1.0.0-SNAPSHOT.jar"
```

Response example:

```json
{
    "pluginId": "remote-log",
    "toolType": "log_read",
    "displayName": "RemoteLogQuery",
    "description": "Query remote Loki logs",
    "version": "1.0.0",
    "isDefault": false,
    "enabled": true,
    "system": false,
    "jarPath": "/data/snap-agent/plugins/remote-log/plugin.jar"
}
```

Upload flow:
1. JAR saved to `${upload-skills-dir}/plugins/{pluginId}/plugin.jar`
2. `URLClassLoader` created (parent = main application ClassLoader)
3. Metadata scanned (`@ToolPluginAnnotation` preferred, `plugin-info.yml` fallback)
4. `ToolProvider` instantiated (requires no-arg constructor)
5. `PluginDescriptor` constructed + registered in `PluginRegistry`
6. `isDefault` defaults to `false` — call `PUT /tools/plugins/{id}/default` to set as default

---

## 5. Configuration

### YAML Configuration

```yaml
snap-agent:
  tools:
    remote-log:
      base-url: http://loki:3100
      timeout-seconds: 30
      max-lines: 500
```

### Reading Configuration in a Plugin

```java
@Override
public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
    // ctx.getPluginContext() is injected by ToolDispatcher for custom plugins
    // system plugins have null pluginContext (they use Spring @Value injection)
    if (ctx.getPluginContext() != null) {
        Map<String, Object> config = ctx.getPluginContext().getConfiguration();
        String baseUrl = (String) config.get("base-url");
        int timeout = (Integer) config.getOrDefault("timeout-seconds", 30);
        // use configuration...
    }
    // ...
}
```

Configuration source: all properties under `snap-agent.tools.{pluginId}.*`.

---

## 6. Disable / Enable / Set Default / Unregister

```bash
# Disable a plugin (removed from LLM tool list, but kept in registry)
curl -X POST http://localhost:8080/skills-agent/tools/plugins/remote-log/disable \
  -H "Authorization: Basic ..."

# Enable a plugin
curl -X POST http://localhost:8080/skills-agent/tools/plugins/remote-log/enable \
  -H "Authorization: Basic ..."

# Set as default plugin for this toolType
curl -X PUT http://localhost:8080/skills-agent/tools/plugins/remote-log/default \
  -H "Authorization: Basic ..."

# Unregister (system plugins return 403)
curl -X DELETE http://localhost:8080/skills-agent/tools/plugins/remote-log \
  -H "Authorization: Basic ..."
```

| Operation | Endpoint | Permission |
|-----------|----------|------------|
| Disable | `POST /tools/plugins/{id}/disable` | `snap-agent:plugin:manage` |
| Enable | `POST /tools/plugins/{id}/enable` | `snap-agent:plugin:manage` |
| Set default | `PUT /tools/plugins/{id}/default` | `snap-agent:plugin:manage` |
| Unregister | `DELETE /tools/plugins/{id}` | `snap-agent:plugin:manage` |

> In production, prefer **disable** over unregister — ClassLoader GC is not guaranteed to reclaim immediately.

---

## 7. Using a Plugin in a Skill

### Specify plugin via pluginOverrides

```bash
curl -X POST http://localhost:8080/skills-agent/runs \
  -H "Authorization: Basic ..." \
  -H "Content-Type: application/json" \
  -d '{
    "skillName": "log-analysis",
    "inputs": {"keyword": "timeout"},
    "pluginOverrides": {
      "log_read": "remote-log"
    }
  }'
```

### How It Works

```
LLM → tool_use(log_read, args)
    → ToolDispatcher.dispatch("log_read", args, ctx)
    → ctx.pluginOverrides["log_read"] = "remote-log"
    → registry.getPlugin("remote-log")
    → plugin.provider.execute(args, ctx)
    → ToolResult
```

- The LLM only sees `toolType` (e.g., `log_read`), not the plugin
- `ToolDispatcher` first checks `ctx.pluginOverrides[toolType]`, then `registry.getDefault(toolType)`
- Without `pluginOverrides`, the default plugin is used — backward compatible

---

## 8. Testing a Plugin

### Unit Test Template

The generated test skeleton includes 4 tests:

```java
class RemoteLogToolProviderTest {

    private RemoteLogToolProvider provider;

    @BeforeEach
    void setUp() {
        provider = new RemoteLogToolProvider();
    }

    @Test
    void nameReturnsPluginId() {
        assertThat(provider.name()).isEqualTo("remote-log");
    }

    @Test
    void schemaContainsToolType() {
        assertThat(provider.schema()).contains("\"log_read\"");
    }

    @Test
    void executeWithNullArgsReturnsError() {
        ToolResult result = provider.execute(null, null);
        assertThat(result.isError()).isTrue();
    }

    @Test
    void executeWithValidQueryReturnsSuccess() {
        Map<String, Object> args = new HashMap<>();
        args.put("query", "test");
        ToolResult result = provider.execute(args, null);
        assertThat(result.isSuccess()).isTrue();
    }
}
```

### Local Verification Flow

```bash
# 1. Compile + run tests
cd my-remote-log-plugin
mvn clean test

# 2. Package
mvn package

# 3. Upload to local SnapAgent instance
curl -X POST http://localhost:8080/skills-agent/tools/plugins/upload \
  -F "jar=@target/my-remote-log-plugin-1.0.0-SNAPSHOT.jar"

# 4. Verify registration
curl http://localhost:8080/skills-agent/tools/plugins

# 5. Execute skill to verify
curl -X POST http://localhost:8080/skills-agent/runs \
  -H "Content-Type: application/json" \
  -d '{"skillName":"log-analysis","inputs":{"keyword":"test"},"pluginOverrides":{"log_read":"remote-log"}}'
```

---

## 9. Limitations & Best Practices

### ClassLoader Unloading

Java ClassLoader GC **does not guarantee immediate reclamation**. After unregistering a plugin, its `URLClassLoader` is closed and awaits GC, but static state, threads, or unclosed resources may leak.

- **Recommended**: use `disable` instead of `unregister` in production
- **Monitor**: watch ClassLoader count; investigate unexpected growth

### ThreadLocal

Plugins execute in a separate `URLClassLoader` and **cannot access the host application's ThreadLocal**. All request-scoped information (userId, taskId) must be obtained from `ToolContext`:

```java
@Override
public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
    String userId = ctx.getUserId();   // correct
    // String userId = (String) ThreadLocalContext.get();  // wrong — unavailable
}
```

### ToolResult.content Must Be String

`ToolResult.getContent()` returns `String`, ensuring cross-ClassLoader safety. Do not attempt to return custom objects.

### Threads

Do NOT start **non-daemon threads** inside a Plugin. Non-daemon threads prevent JVM shutdown.

### Dependency Compatibility

Plugins can access host Spring Beans (e.g., `DataSource`). Ensure host Bean versions are compatible with the plugin's compile-time dependencies. The `snap-agent-core` API stable version guarantees backward compatibility.
```

- [ ] **Step 2: 验证英文指南内容**

Run:
```bash
cd /Users/HuaSheng.Song/IdeaProjects/skills-agent/.worktrees/feat-v0.5-plugin-improvement
grep -c '^## ' docs/site/plugins/en/plugin-development-guide.md
```

Expected: `9`

验证所有 9 节标题存在:
```bash
grep '^## ' docs/site/plugins/en/plugin-development-guide.md
```

Expected 输出包含:
```
## 1. Core Concepts
## 2. Quick Start
## 3. Packaging
## 4. Upload
## 5. Configuration
## 6. Disable / Enable / Set Default / Unregister
## 7. Using a Plugin in a Skill
## 8. Testing a Plugin
## 9. Limitations & Best Practices
```

- [ ] **Step 3: 提交英文指南**

```bash
cd /Users/HuaSheng.Song/IdeaProjects/skills-agent/.worktrees/feat-v0.5-plugin-improvement
git add docs/site/plugins/en/plugin-development-guide.md
git commit -m "docs: add English plugin development guide (9 sections)"
```

---

### Task 11c: 更新架构文档 (zh + en)

**Files:**
- Modify: `docs/site/plugins/zh/tool-plugin-architecture.md`
- Modify: `docs/site/plugins/en/tool-plugin-architecture.md`

- [ ] **Step 1: 在中文架构文档末尾追加 v0.5 章节**

在 `docs/site/plugins/zh/tool-plugin-architecture.md` 文件末尾 (第 633 行之后) 追加以下内容。使用 Edit 工具，在文件最后一行之后添加:

```markdown

---

## 9. v0.5 Plugin 架构（PluginDescriptor + PluginRegistry + 路由）

> 版本：v0.5 | 更新日期：2026-07-22

### 9.1 概述

v0.5 引入真正的 Plugin 抽象，取代 v1.0 的元数据层。新架构支持:

- 运行时注册/反注册/启停/设默认
- 同一 `toolType` 多个 Plugin (默认 + 显式覆盖)
- JAR 上传 + URLClassLoader 隔离
- `pluginOverrides` 路由 — LLM 只看 `toolType`，dispatcher 按覆盖路由

### 9.2 核心组件

```
┌──────────────────────────────────────────────────────────┐
│                     ToolDispatcher                        │
│   dispatch(toolType, args, ctx):                          │
│     1. ctx.pluginOverrides[toolType] → pluginId           │
│     2. registry.getDefault(toolType) → pluginId           │
│     3. plugin.provider.execute(args, ctx)                 │
└──────────────┬───────────────────────────────────────────┘
               │
               ▼
┌──────────────────────────────────────────────────────────┐
│                     PluginRegistry                        │
│   plugins: Map<pluginId, PluginDescriptor>               │
│   byType: Map<toolType, List<PluginDescriptor>>           │
│   register / unregister / enable / disable / setDefault  │
└──────────────┬───────────────────────────────────────────┘
               │
               ▼
┌──────────────────────────────────────────────────────────┐
│                     PluginDescriptor                     │
│   pluginId, toolType, displayName, version, isDefault,   │
│   enabled, system, provider (ToolProvider),              │
│   classLoader (URLClassLoader|null), jarPath|null         │
└──────────────────────────────────────────────────────────┘
```

### 9.3 PluginDescriptor

```java
public final class PluginDescriptor {
    private final String pluginId;          // 唯一标识，e.g., "remote-log"
    private final String toolType;          // LLM 调用的工具名，e.g., "log_read"
    private final String displayName;
    private final String description;
    private final String version;
    private volatile boolean isDefault;     // 该 toolType 的默认 plugin
    private volatile boolean enabled;       // 是否启用
    private final boolean system;           // true=内置不可删除
    private final ToolProvider provider;    // 实现
    private final ClassLoader classLoader;  // 自定义 plugin 的 URLClassLoader
    private final Path jarPath;             // 自定义 plugin 的 JAR 路径
    private final PluginContext pluginContext;  // 配置上下文
}
```

### 9.4 @ToolPluginAnnotation 注解

```java
@ToolPluginAnnotation(
    id = "remote-log",
    toolType = "log_read",
    displayName = "远程日志查询",
    description = "查询 Loki 历史日志",
    version = "1.2.0"
)
public class RemoteLogToolProvider implements ToolProvider { ... }
```

注解优先于 `plugin-info.yml`。扫描顺序: 先找注解类，若多个则用 YAML 指定的 `providerClass`，若无注解则用 YAML 全字段。

### 9.5 ToolContext 扩展

`ToolContext` 新增 `pluginOverrides` (Map<toolType, pluginId>) 和 `pluginContext` 字段:

- `pluginOverrides`: 由 `POST /runs` 请求体传入，dispatcher 按 override 路由
- `pluginContext`: 由 `ToolDispatcher` 从 `PluginDescriptor` 取出注入，plugin 可读取配置

### 9.6 Built-in 工具的透明包装

启动时，所有 `@Component ToolProvider` bean 自动包装为 system plugin:

| 字段 | 值 |
|------|---|
| `pluginId` | `ToolProvider.name()` |
| `toolType` | `ToolProvider.name()` |
| `system` | `true` (不可 unregister) |
| `isDefault` | `true` (每 toolType 第一个注册者) |

**向后兼容**: 现有 skill 不传 `pluginOverrides` → 走 default → 命中 system plugin → 行为与 v1.0 完全一致。

### 9.7 v1.0 ToolPlugin SPI 兼容性

v1.0 的 `ToolPlugin` 接口 (元数据层: `name()` / `version()` / `description()` / `toolNames()`) **已被 v0.5 架构取代**，但接口保留以兼容现有实现:

- v0.5 的 `@ToolPluginAnnotation` 是新的元数据声明方式 (注解优先)
- v1.0 的 `ToolPlugin` 接口仍可实现，但仅用于元数据查询，不影响路由
- `GET /tools/plugins` 响应格式已扩展 (新增 `pluginId` / `toolType` / `system` / `jarPath` 字段)

> 迁移建议: 新 Plugin 使用 `@ToolPluginAnnotation` + `ToolProvider` 实现，不再实现 `ToolPlugin` 接口。
```

- [ ] **Step 2: 验证中文架构文档更新**

Run:
```bash
cd /Users/HuaSheng.Song/IdeaProjects/skills-agent/.worktrees/feat-v0.5-plugin-improvement
grep '^## 9\.' docs/site/plugins/zh/tool-plugin-architecture.md
```

Expected: 输出包含 `## 9. v0.5 Plugin 架构（PluginDescriptor + PluginRegistry + 路由）`

验证子节存在:
```bash
grep '^### 9\.' docs/site/plugins/zh/tool-plugin-architecture.md
```

Expected 输出包含 7 个子节 (9.1-9.7)。

- [ ] **Step 3: 在英文架构文档末尾追加 v0.5 章节**

在 `docs/site/plugins/en/tool-plugin-architecture.md` 文件末尾 (第 634 行之后) 追加以下内容:

```markdown

---

## 9. v0.5 Plugin Architecture (PluginDescriptor + PluginRegistry + Routing)

> Version: v0.5 | Updated: 2026-07-22

### 9.1 Overview

v0.5 introduces a true Plugin abstraction, superseding the v1.0 metadata layer. The new architecture supports:

- Runtime register/unregister/enable/disable/set-default
- Multiple Plugins per `toolType` (default + explicit override)
- JAR upload + URLClassLoader isolation
- `pluginOverrides` routing — LLM only sees `toolType`, dispatcher routes by override

### 9.2 Core Components

```
┌──────────────────────────────────────────────────────────┐
│                     ToolDispatcher                        │
│   dispatch(toolType, args, ctx):                          │
│     1. ctx.pluginOverrides[toolType] → pluginId           │
│     2. registry.getDefault(toolType) → pluginId           │
│     3. plugin.provider.execute(args, ctx)                 │
└──────────────┬───────────────────────────────────────────┘
               │
               ▼
┌──────────────────────────────────────────────────────────┐
│                     PluginRegistry                        │
│   plugins: Map<pluginId, PluginDescriptor>               │
│   byType: Map<toolType, List<PluginDescriptor>>           │
│   register / unregister / enable / disable / setDefault  │
└──────────────┬───────────────────────────────────────────┘
               │
               ▼
┌──────────────────────────────────────────────────────────┐
│                     PluginDescriptor                     │
│   pluginId, toolType, displayName, version, isDefault,   │
│   enabled, system, provider (ToolProvider),              │
│   classLoader (URLClassLoader|null), jarPath|null         │
└──────────────────────────────────────────────────────────┘
```

### 9.3 PluginDescriptor

```java
public final class PluginDescriptor {
    private final String pluginId;          // unique id, e.g., "remote-log"
    private final String toolType;          // LLM tool name, e.g., "log_read"
    private final String displayName;
    private final String description;
    private final String version;
    private volatile boolean isDefault;     // default plugin for this toolType
    private volatile boolean enabled;       // whether enabled
    private final boolean system;           // true=built-in, cannot unregister
    private final ToolProvider provider;    // implementation
    private final ClassLoader classLoader;  // URLClassLoader for custom plugins
    private final Path jarPath;             // JAR path for custom plugins
    private final PluginContext pluginContext;  // config context
}
```

### 9.4 @ToolPluginAnnotation Annotation

```java
@ToolPluginAnnotation(
    id = "remote-log",
    toolType = "log_read",
    displayName = "Remote Log Query",
    description = "Query Loki historical logs",
    version = "1.2.0"
)
public class RemoteLogToolProvider implements ToolProvider { ... }
```

The annotation takes precedence over `plugin-info.yml`. Scan order: annotation classes first; if multiple, use YAML `providerClass`; if no annotation, use all YAML fields.

### 9.5 ToolContext Extensions

`ToolContext` adds `pluginOverrides` (Map<toolType, pluginId>) and `pluginContext` fields:

- `pluginOverrides`: passed from `POST /runs` request body, dispatcher routes by override
- `pluginContext`: injected by `ToolDispatcher` from `PluginDescriptor`, plugin reads configuration

### 9.6 Built-in Tool Transparent Wrapping

At startup, all `@Component ToolProvider` beans are auto-wrapped as system plugins:

| Field | Value |
|-------|-------|
| `pluginId` | `ToolProvider.name()` |
| `toolType` | `ToolProvider.name()` |
| `system` | `true` (cannot unregister) |
| `isDefault` | `true` (first registered per toolType) |

**Backward compatibility**: existing skills without `pluginOverrides` → default → system plugin → behavior identical to v1.0.

### 9.7 v1.0 ToolPlugin SPI Compatibility

The v1.0 `ToolPlugin` interface (metadata layer: `name()` / `version()` / `description()` / `toolNames()`) **has been superseded by the v0.5 architecture**, but the interface is retained for compatibility:

- v0.5's `@ToolPluginAnnotation` is the new metadata declaration method (annotation preferred)
- v1.0's `ToolPlugin` interface can still be implemented, but only for metadata queries, not routing
- `GET /tools/plugins` response format has been extended (new `pluginId` / `toolType` / `system` / `jarPath` fields)

> Migration recommendation: new Plugins should use `@ToolPluginAnnotation` + `ToolProvider` implementation, and no longer implement the `ToolPlugin` interface.
```

- [ ] **Step 4: 验证英文架构文档更新**

Run:
```bash
cd /Users/HuaSheng.Song/IdeaProjects/skills-agent/.worktrees/feat-v0.5-plugin-improvement
grep '^## 9\.' docs/site/plugins/en/tool-plugin-architecture.md
```

Expected: 输出包含 `## 9. v0.5 Plugin Architecture (PluginDescriptor + PluginRegistry + Routing)`

验证子节存在:
```bash
grep '^### 9\.' docs/site/plugins/en/tool-plugin-architecture.md
```

Expected 输出包含 7 个子节 (9.1-9.7)。

- [ ] **Step 5: 提交架构文档更新**

```bash
cd /Users/HuaSheng.Song/IdeaProjects/skills-agent/.worktrees/feat-v0.5-plugin-improvement
git add docs/site/plugins/zh/tool-plugin-architecture.md docs/site/plugins/en/tool-plugin-architecture.md
git commit -m "docs: add v0.5 plugin architecture section to tool-plugin-architecture docs

Adds §9 explaining PluginDescriptor + PluginRegistry + routing model.
Marks v1.0 ToolPlugin SPI as superseded but interface retained for compat."
```

---

## 完成检查清单

- [ ] `snap-agent-plugin-archetype/` 模块创建，包含 6 个文件
- [ ] 父 POM `<modules>` 包含 `snap-agent-plugin-archetype`
- [ ] `mvn install -pl snap-agent-plugin-archetype` 成功
- [ ] archetype 生成测试通过 (生成的项目结构正确、`${property}` 全部替换)
- [ ] `docs/site/plugins/zh/plugin-development-guide.md` 存在，9 节齐全
- [ ] `docs/site/plugins/en/plugin-development-guide.md` 存在，9 节齐全
- [ ] `docs/site/plugins/zh/tool-plugin-architecture.md` 追加 §9 (7 个子节)
- [ ] `docs/site/plugins/en/tool-plugin-architecture.md` 追加 §9 (7 个子节)
- [ ] 所有模板文件无 TODO 占位符
- [ ] 所有提交完成
