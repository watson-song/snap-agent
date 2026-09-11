---
name: snap-agent-integration
description: 一键集成 SnapAgent 到新宿主项目 — 自动探测环境、安装依赖、生成知识库和 CodeGraph
version: 1.0.0
type: integration-tool
triggers:
  - 集成 SnapAgent
  - 接入 SnapAgent
  - 集成 AI Agent 到这个项目
  - snap agent integration
  - 一键集成
input:
  - project_root: 宿主项目根路径（必填）
  - llm_config: LLM 配置（可选，交互输入）
  - db_config: DB 配置（可选，从现有配置推断）
output:
  - lib/ 目录
  - pom.xml 修改
  - application-snap.yml
  - docs/knowledge/*.md
  - data/codegraph.mv.db
  - INTEGRATION_REPORT.md
author: SnapAgent
---

# SnapAgent 一键集成工具

> **定位**：集成阶段工具，在新宿主项目本地执行，自动完成所有集成步骤。
> **适用**：任何 Java/Spring Boot 2.x 项目。
> **产出**：完整的 SnapAgent 集成，包括依赖、配置、知识库、CodeGraph、集成报告。

你是一个 SnapAgent 集成专家。你的任务是将 SnapAgent 集成到新的宿主项目中，自动完成所有配置和知识生成。

## 核心规则

1. **自动探测** — 扫描项目结构，推断 Spring Boot 版本、Java 版本、安全框架等
2. **最小侵入** — 只修改必要的文件，不改变宿主项目的业务逻辑
3. **可回滚** — 所有修改记录在集成报告中，方便回滚
4. **完整验证** — 集成完成后自动验证编译、启动、LLM 连通

## 执行流程

### ═══ 阶段 1：环境探测（只读）═══

#### Step 1: 项目结构扫描

读取 `{project_root}` 根目录：

```
1. 识别构建工具：pom.xml / build.gradle
2. 识别 Spring Boot 版本：
   - 从 pom.xml 的 <parent> 或 <dependencyManagement> 提取
   - 记录 major version (2.x / 3.x)
3. 识别 Java 版本：
   - 从 <java.version> 或 <maven.compiler.source> 提取
4. 识别模块列表：
   - 从 <modules> 提取所有子模块
5. 识别 starter 模块：
   - 包含 spring-boot-maven-plugin 的模块
   - 通常是 xxx-starter / xxx-web / xxx-app
6. 识别安全框架：
   - Spring Security (spring-security-config)
   - Shiro (shiro-spring)
   - 自定义 (JwtFilter / AuthInterceptor)
7. 识别数据源：
   - 从 application.yml/properties 提取 JDBC URL
   - 识别数据库类型 (MySQL/PostgreSQL/H2)
8. 规模评估：
   - 统计 src/main/java 下的 Java 文件数
   - < 500: 小项目 → 全量扫描
   - 500-2000: 中型项目 → 分模块扫描
   - > 2000: 大型项目 → 核心模块优先
```

输出：`{project_root}/.snap-agent/probe-result.json`

#### Step 2: 用户确认

向用户展示探测结果，确认关键信息：

```
探测结果：
- Spring Boot: 2.7.18
- Java: 1.8
- 模块: [core, biz, sys, starter]
- Starter 模块: starter
- 安全框架: Spring Security
- 数据库: MySQL (scpdrp_saas)

是否继续集成？[Y/n]
```

### ═══ 阶段 2：安装 SnapAgent（写入）═══

#### Step 3: 复制 lib/

根据探测到的 Spring Boot 版本，复制对应的 SnapAgent JAR：

```bash
# 创建 lib/ 目录
mkdir -p lib/cn/watsontech/snapagent/{snap-agent-core,snap-agent-spring-boot-2x-starter,snap-agent-parent}/2.0.0-SNAPSHOT

# 从本地 .m2 复制（或从远程下载）
M2="$HOME/.m2/repository/cn/watsontech/snapagent"
VER="2.0.0-SNAPSHOT"

cp "$M2/snap-agent-parent/$VER/"*.pom lib/cn/watsontech/snapagent/snap-agent-parent/$VER/
cp "$M2/snap-agent-core/$VER/"*.jar lib/cn/watsontech/snapagent/snap-agent-core/$VER/
cp "$M2/snap-agent-core/$VER/"*.pom lib/cn/watsontech/snapagent/snap-agent-core/$VER/
cp "$M2/snap-agent-spring-boot-2x-starter/$VER/"*.jar lib/cn/watsontech/snapagent/snap-agent-spring-boot-2x-starter/$VER/
cp "$M2/snap-agent-spring-boot-2x-starter/$VER/"*.pom lib/cn/watsontech/snapagent/snap-agent-spring-boot-2x-starter/$VER/
```

**注意**：如果本地 .m2 没有，需要先 `mvn install` SnapAgent 项目。

#### Step 4: 修改 starter 模块 pom.xml

在 starter 模块的 pom.xml 中添加：

```xml
<dependencies>
    <!-- SnapAgent Core -->
    <dependency>
        <groupId>cn.watsontech.snapagent</groupId>
        <artifactId>snap-agent-core</artifactId>
        <version>2.0.0-SNAPSHOT</version>
        <scope>system</scope>
        <systemPath>${maven.multiModuleProjectDirectory}/lib/cn/watsontech/snapagent/snap-agent-core/2.0.0-SNAPSHOT/snap-agent-core-2.0.0-SNAPSHOT.jar</systemPath>
    </dependency>

    <!-- SnapAgent Spring Boot 2.x Starter -->
    <dependency>
        <groupId>cn.watsontech.snapagent</groupId>
        <artifactId>snap-agent-spring-boot-2x-starter</artifactId>
        <version>2.0.0-SNAPSHOT</version>
        <scope>system</scope>
        <systemPath>${maven.multiModuleProjectDirectory}/lib/cn/watsontech/snapagent/snap-agent-spring-boot-2x-starter/2.0.0-SNAPSHOT/snap-agent-spring-boot-2x-starter-2.0.0-SNAPSHOT.jar</systemPath>
    </dependency>
</dependencies>

<build>
    <plugins>
        <!-- Maven Install Plugin: 自动安装 lib/ 到本地 .m2 -->
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-install-plugin</artifactId>
            <version>3.1.1</version>
            <executions>
                <execution>
                    <id>install-snap-agent-parent</id>
                    <phase>initialize</phase>
                    <goals><goal>install-file</goal></goals>
                    <configuration>
                        <file>${maven.multiModuleProjectDirectory}/lib/cn/watsontech/snapagent/snap-agent-parent/2.0.0-SNAPSHOT/snap-agent-parent-2.0.0-SNAPSHOT.pom</file>
                        <groupId>cn.watsontech.snapagent</groupId>
                        <artifactId>snap-agent-parent</artifactId>
                        <version>2.0.0-SNAPSHOT</version>
                        <packaging>pom</packaging>
                    </configuration>
                </execution>
                <execution>
                    <id>install-snap-agent-core</id>
                    <phase>initialize</phase>
                    <goals><goal>install-file</goal></goals>
                    <configuration>
                        <file>${maven.multiModuleProjectDirectory}/lib/cn/watsontech/snapagent/snap-agent-core/2.0.0-SNAPSHOT/snap-agent-core-2.0.0-SNAPSHOT.jar</file>
                        <groupId>cn.watsontech.snapagent</groupId>
                        <artifactId>snap-agent-core</artifactId>
                        <version>2.0.0-SNAPSHOT</version>
                        <packaging>jar</packaging>
                        <pomFile>${maven.multiModuleProjectDirectory}/lib/cn/watsontech/snapagent/snap-agent-core/2.0.0-SNAPSHOT/snap-agent-core-2.0.0-SNAPSHOT.pom</pomFile>
                    </configuration>
                </execution>
                <execution>
                    <id>install-snap-agent-spring-boot-2x-starter</id>
                    <phase>initialize</phase>
                    <goals><goal>install-file</goal></goals>
                    <configuration>
                        <file>${maven.multiModuleProjectDirectory}/lib/cn/watsontech/snapagent/snap-agent-spring-boot-2x-starter/2.0.0-SNAPSHOT/snap-agent-spring-boot-2x-starter-2.0.0-SNAPSHOT.jar</file>
                        <groupId>cn.watsontech.snapagent</groupId>
                        <artifactId>snap-agent-spring-boot-2x-starter</artifactId>
                        <version>2.0.0-SNAPSHOT</version>
                        <packaging>jar</packaging>
                        <pomFile>${maven.multiModuleProjectDirectory}/lib/cn/watsontech/snapagent/snap-agent-spring-boot-2x-starter/2.0.0-SNAPSHOT/snap-agent-spring-boot-2x-starter-2.0.0-SNAPSHOT.pom</pomFile>
                    </configuration>
                </execution>
            </executions>
        </plugin>

        <!-- Spring Boot Maven Plugin: 包含 system scope JAR -->
        <plugin>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-maven-plugin</artifactId>
            <configuration>
                <includeSystemScope>true</includeSystemScope>
            </configuration>
        </plugin>
    </plugins>
</build>
```

#### Step 5: 生成 application-snap.yml

根据探测到的项目配置，生成 SnapAgent 配置文件：

```yaml
# SnapAgent 配置
snap-agent:
  enabled: true
  base-path: /snap-agent

  # LLM 配置（需要用户填写）
  llm:
    api-type: anthropic
    base-url: ${LLM_BASE_URL:https://api.anthropic.com}
    auth-token: ${LLM_AUTH_TOKEN:}
    model: ${LLM_MODEL:claude-sonnet-4-20250514}
    max-tokens: 8192
    timeout-seconds: 120
    streaming: true

  # Agent 配置
  agent:
    max-turns: 20
    task-timeout-minutes: 30
    max-concurrent-runs-per-user: 5

  # JDBC 工具配置（从项目配置推断）
  jdbc:
    enabled: true
    datasource-bean-name: dataSource
    # URL/用户名/密码从项目现有配置推断

  # 代码图谱
  code-graph:
    enabled: true
    scan-packages:
      - ${project.base-package}
    persistence: h2
    h2-url: jdbc:h2:file:${project.root}/data/codegraph/codegraph
    hot-reload-enabled: true

  # 安全配置（根据探测到的安全框架）
  security:
    framework: ${detected-security-framework}
    whitelist-paths:
      - /snap-agent/health
      - /snap-agent/chat/index.html
      - /snap-agent/chat/index.js
      - /snap-agent/chat/index.css
      - /snap-agent/chat/logo.png
      - /snap-agent/chat/markdown.css
      - /snap-agent/settings.html
      - /snap-agent/js/**
      - /snap-agent/bridge/**
```

#### Step 6: 更新主 application.yml

在主 application.yml 中添加：

```yaml
spring:
  profiles:
    include: snap
```

### ═══ 阶段 3：知识生成（调用 skill）═══

#### Step 7: 生成领域知识文档

调用 `domain-knowledge-discovery` skill：

```
输入：
- project_root: {project_root}
- output_dir: {project_root}/src/main/resources/docs/knowledge
- scan_packages: ${project.base-package}

输出：
- {project_root}/src/main/resources/docs/knowledge/*.md
```

执行流程：
1. 扫描项目结构，提取业务概念
2. 生成领域知识总文档
3. 生成模块业务文档
4. 执行两轮 Review

#### Step 8: 生成 CodeGraph 数据库

使用内置 `CodeGraphCli`（JavaParser AST 解析，输出 H2 图谱文件）。**关键点优先**：用 `scan-mode skills` 只把 skill 文档标注的关键业务类（含完整方法体）写入 H2，而非全项目。

```bash
cd {project_root}
java -cp "snap-agent-spring-boot-2x-starter/target/*.jar:snap-agent-core/target/*.jar" \
  cn.watsontech.snapagent.boot2x.codegraph.CodeGraphCli \
  --project-root . \
  --scan-mode skills \
  --skill-dir docs/skills \
  --output data/codegraph
```

> 若希望扫描整个包（非关键点优先），改用 `--scan-packages ${project-package}` 并省略 `--scan-mode skills`。

输出：
- `data/codegraph.mv.db`（H2 数据库，含关键业务类完整方法体，运行时 `H2CodeGraphIndex` 直接加载，`code_view` 工具离线查看）

### ═══ 阶段 4：验证（自动化测试）═══

#### Step 9: 编译验证

```bash
cd {project_root}
mvn clean package -DskipTests
```

验证：
- 编译成功
- fat JAR 中包含 snap-agent-core 和 snap-agent-spring-boot-2x-starter

#### Step 10: 启动验证

```bash
cd {project_root}
java -jar ${starter-module}/target/${starter-module}-*.jar \
  --snap-agent.llm.auth-token=${LLM_AUTH_TOKEN}
```

验证：
- Health Check: `curl http://localhost:8080/snap-agent/health` → `{"status":"UP"}`
- Chat UI: 打开 `http://localhost:8080/snap-agent/chat/index.html`

#### Step 11: 功能验证

```bash
# 验证 Skills API
curl http://localhost:8080/snap-agent/skills -u demo:demo

# 验证 LLM 连通
curl -X POST http://localhost:8080/snap-agent/runs \
  -u demo:demo \
  -H "Content-Type: application/json" \
  -d '{"skillId":"health-check","inputs":{"message":"你好"}}'

# 验证 CodeGraph
curl http://localhost:8080/snap-agent/code-graph/status -u demo:demo
```

### ═══ 阶段 5：输出报告 ═══

#### Step 12: 生成集成报告

输出：`{project_root}/INTEGRATION_REPORT.md`

```markdown
# SnapAgent 集成报告

## 项目信息
- 项目名称: ${project-name}
- Spring Boot: ${spring-boot-version}
- Java: ${java-version}
- 集成时间: ${timestamp}

## 集成步骤
1. ✅ 环境探测
2. ✅ 安装 SnapAgent (lib/ + pom.xml)
3. ✅ 生成配置文件 (application-snap.yml)
4. ✅ 生成领域知识文档 (${knowledge-docs-count} 个文件)
5. ✅ 生成 CodeGraph 数据库 (${codegraph-nodes} 节点, ${codegraph-edges} 边)
6. ✅ 编译验证
7. ✅ 启动验证
8. ✅ 功能验证

## 修改的文件
- ${starter-module}/pom.xml
- ${starter-module}/src/main/resources/application.yml
- 新增: lib/ 目录
- 新增: src/main/resources/docs/knowledge/*.md
- 新增: data/codegraph.mv.db

## 待配置项
- [ ] LLM_AUTH_TOKEN 环境变量
- [ ] DB 密码（如果使用独立数据源）
- [ ] 安全白名单（根据实际路径调整）

## 后续建议
- 访问 Chat UI: http://localhost:8080/snap-agent/chat/index.html
- 查看 Settings: http://localhost:8080/snap-agent/settings.html
- 创建业务 Skill: 参考 docs/skills/ 目录
```

## 错误处理

| 场景 | 处理 |
|------|------|
| 本地 .m2 没有 SnapAgent | 提示用户先 `mvn install` SnapAgent 项目 |
| Spring Boot 3.x | 提示使用 snap-agent-spring-boot-3x-starter |
| 多模块项目找不到 starter | 列出所有模块，让用户选择 |
| 编译失败 | 输出错误日志，回滚 pom.xml 修改 |
| 启动失败 | 输出错误日志，检查配置 |
| LLM 连通失败 | 提示检查 LLM_AUTH_TOKEN |

## 版本历史

- **v1.0.0**：初始版本，支持 Spring Boot 2.x 项目一键集成
