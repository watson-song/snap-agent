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

**方式 A — 从源码构建安装：**

```bash
git clone <repo-url> snap-agent && cd snap-agent && mvn clean install -DskipTests
```

> 此方式将 jar 安装到 `~/.m2/repository`，仅适用于本机开发。CI/CD 构建Agent通常没有预装的 snap-agent，需用方式 B。

**方式 B — 离线 CI/CD（项目内 `lib/` 文件仓库）：**

适用于构建环境无法访问外部 Maven 仓库、或 `~/.m2` 不持久化的场景。将 jar+pom 放入项目内的 `lib/` 目录，Maven 直接从文件系统解析，不访问外网。

1. 从 [GitHub Release](https://github.com/watson-song/snap-agent/releases) 下载 `snap-agent-core-*.jar`、`snap-agent-core-*.pom`、`snap-agent-spring-boot-2x-starter-*.jar`、`snap-agent-spring-boot-2x-starter-*.pom`。

2. 按 Maven 仓库布局放入项目根目录的 `lib/`：

```
项目根/
├── pom.xml
└── lib/
    └── cn/watsontech/snapagent/
        ├── snap-agent-core/<version>/
        │   ├── snap-agent-core-<version>.jar
        │   └── snap-agent-core-<version>.pom
        └── snap-agent-spring-boot-2x-starter/<version>/
            ├── snap-agent-spring-boot-2x-starter-<version>.jar
            └── snap-agent-spring-boot-2x-starter-<version>.pom
```

3. 在宿主 `pom.xml` 中添加本地文件仓库和依赖：

```xml
<repositories>
    <repository>
        <id>snap-agent-local</id>
        <!-- ⚠ 多模块项目必须用 ${maven.multiModuleProjectDirectory}，不能用 ${project.basedir} -->
        <!-- ${project.basedir} 在子模块构建时解析为子模块目录，Maven 找不到根目录的 lib/ -->
        <!-- ${maven.multiModuleProjectDirectory}（Maven 3.3.1+）始终指向项目根目录 -->
        <url>file://${maven.multiModuleProjectDirectory}/lib</url>
        <releases>
            <enabled>true</enabled>
            <checksumPolicy>ignore</checksumPolicy>
        </releases>
        <snapshots>
            <enabled>false</enabled>
        </snapshots>
    </repository>
</repositories>
```

> **多模块项目（重要）**：`<repository>` 的 URL 必须用 `${maven.multiModuleProjectDirectory}` 而非 `${project.basedir}`。后者在子模块构建时解析为子模块目录而非项目根目录，Maven 找不到 `lib/` 会静默回退到远程仓库，导致 CI/CD 报 `Could not find artifact`。`${maven.multiModuleProjectDirectory}` 是 Maven 3.3.1+ 内置变量，始终指向多模块项目的根目录。

> 将 `lib/` 目录提交到 Git，确保 CI/CD checkout 后可直接解析。

> **⚠ 如果 CI/CD 的 `settings.xml` 配置了 `<mirror>`**（如 `<mirrorOf>*</mirror>` 重定向到 Artifactory/Nexus），`file://` 本地文件仓库会被 mirror 全局拦截，Maven 绕过 `lib/` 直接请求远程仓库。此时需改用 **方式 B-fallback（system scope）**：

```xml
<!-- system scope 方式：直接从文件系统加载 JAR，绕过 Maven 仓库解析 -->
<!-- 需显式声明 starter 和 core 两个依赖（system scope 不解析传递依赖） -->
<dependencies>
    <dependency>
        <groupId>cn.watsontech.snapagent</groupId>
        <artifactId>snap-agent-spring-boot-2x-starter</artifactId>
        <version><version></version>
        <scope>system</scope>
        <systemPath>${maven.multiModuleProjectDirectory}/lib/cn/watsontech/snapagent/snap-agent-spring-boot-2x-starter/<version>/snap-agent-spring-boot-2x-starter-<version>.jar</systemPath>
    </dependency>
    <dependency>
        <groupId>cn.watsontech.snapagent</groupId>
        <artifactId>snap-agent-core</artifactId>
        <version><version></version>
        <scope>system</scope>
        <systemPath>${maven.multiModuleProjectDirectory}/lib/cn/watsontech/snapagent/snap-agent-core/<version>/snap-agent-core-<version>.jar</systemPath>
    </dependency>
</dependencies>

<!-- spring-boot-maven-plugin 必须配置 includeSystemScope，否则 system scope JAR 不会打入 fat JAR -->
<plugin>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-maven-plugin</artifactId>
    <configuration>
        <includeSystemScope>true</includeSystemScope>
    </configuration>
</plugin>
```

> system scope 的代价：不解析传递依赖，必须手动声明 `snap-agent-core`。但它是 mirror 拦截场景下唯一可行方案。

**方式 C — 私有 Nexus 仓库：**

将 `snap-agent-core` 和 `snap-agent-spring-boot-2x-starter` 的 JAR deploy 到 Nexus 后在宿主项目中直接引用依赖即可。

## 第二步：添加 Maven 依赖

在宿主项目的 `pom.xml` 中添加：

```xml
<dependency>
    <groupId>cn.watsontech.snapagent</groupId>
    <artifactId>snap-agent-spring-boot-2x-starter</artifactId>
    <version>2.0.0-SNAPSHOT</version>
</dependency>
```

Starter 会自动引入 `snap-agent-core`。以下依赖如果宿主项目已有则无需重复添加：

- `spring-boot-starter-web`（宿主已有）
- `spring-boot-starter-security`（可选，用于鉴权适配）
- `mysql-connector-java`（可选，JDBC 工具需要）
- `spring-boot-starter-data-redis`（可选，Redis 工具需要）
- `springdoc-openapi-ui`（可选，自动生成 Swagger UI / OpenAPI 3 文档，见下方）
- `com.squareup.okhttp3:okhttp`（**必需**，LLM 流式调用依赖，Starter 中为 optional。版本 >= 4.9）
- `com.h2database:h2`（可选，代码图谱 H2 持久化需要）

### 可选：启用 Swagger UI / OpenAPI 3

Starter 已声明 `springdoc-openapi-ui` 为 `optional` 依赖。宿主项目在 `pom.xml` 中添加以下依赖即可获得：

- `GET /swagger-ui.html` — 可视化 API 文档
- `GET /v3/api-docs` — OpenAPI 3 JSON

```xml
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-ui</artifactId>
    <version>1.7.0</version>
</dependency>
```

> 版本 1.7.0 是最后兼容 Spring Boot 2.x 的版本。Spring Boot 3.x 请用 `springdoc-openapi-starter-webmvc-ui`。

### system scope 模式（CI/CD 无内部 Maven 仓库时）

如果 CI/CD 环境的 Maven `settings.xml` 配置了 `<mirrorOf>*</mirrorOf>` 拦截所有仓库请求，且 snap-agent 未发布到内部 Artifactory，可以使用 `system scope` 绕过仓库解析：

1. 在项目根目录创建 `lib/` 目录，按 Maven 仓库布局存放 JAR + POM：
   ```
   lib/cn/watsontech/snapagent/snap-agent-core/2.0.0-SNAPSHOT/
   ├── snap-agent-core-2.0.0-SNAPSHOT.jar
   └── snap-agent-core-2.0.0-SNAPSHOT.pom
   lib/cn/watsontech/snapagent/snap-agent-spring-boot-2x-starter/2.0.0-SNAPSHOT/
   ├── snap-agent-spring-boot-2x-starter-2.0.0-SNAPSHOT.jar
   └── snap-agent-spring-boot-2x-starter-2.0.0-SNAPSHOT.pom
   ```

2. pom.xml 中添加 system scope 依赖：
   ```xml
   <dependency>
       <groupId>cn.watsontech.snapagent</groupId>
       <artifactId>snap-agent-spring-boot-2x-starter</artifactId>
       <version>2.0.0-SNAPSHOT</version>
       <scope>system</scope>
       <systemPath>${maven.multiModuleProjectDirectory}/lib/cn/watsontech/snapagent/snap-agent-spring-boot-2x-starter/2.0.0-SNAPSHOT/snap-agent-spring-boot-2x-starter-2.0.0-SNAPSHOT.jar</systemPath>
   </dependency>
   <dependency>
       <groupId>cn.watsontech.snapagent</groupId>
       <artifactId>snap-agent-core</artifactId>
       <version>2.0.0-SNAPSHOT</version>
       <scope>system</scope>
       <systemPath>${maven.multiModuleProjectDirectory}/lib/cn/watsontech/snapagent/snap-agent-core/2.0.0-SNAPSHOT/snap-agent-core-2.0.0-SNAPSHOT.jar</systemPath>
   </dependency>
   ```

3. **关键：system scope 不解析传递依赖**，以下依赖必须显式声明：
   ```xml
   <dependency>
       <groupId>com.squareup.okhttp3</groupId>
       <artifactId>okhttp</artifactId>
       <version>4.9.3</version>
   </dependency>
   <dependency>
   </dependency>
   <dependency>
       <groupId>com.h2database</groupId>
       <artifactId>h2</artifactId>
       <version>1.4.200</version>
   </dependency>
   ```

4. 确保 `spring-boot-maven-plugin` 包含 system scope 依赖：
   ```xml
   <plugin>
       <groupId>org.springframework.boot</groupId>
       <artifactId>spring-boot-maven-plugin</artifactId>
       <configuration>
           <includeSystemScope>true</includeSystemScope>
       </configuration>
   </plugin>
   ```

## 第三步：配置 application.yml

```yaml
snap-agent:
  enabled: true                          # 必须显式开启
  base-path: /snap-agent               # URL 前缀，可自定义
  builtin-skills-dir: classpath:/docs/skills/    # 只读，打包在 JAR 中
  upload-skills-dir: /tmp/snap-agent-skills       # 读写，重启持久化
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
  logs:
    enabled: true                        # 开启日志分析工具（log_read）
    allowed-paths: [/opt/app/logs]       # 允许读取的日志目录白名单
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

> **简化模式**：如果宿主项目使用 Spring Boot 自动装配的 DataSource（无显式 `@Bean` 定义），可以直接复用宿主的 DataSource：
> ```yaml
> snap-agent:
>   jdbc:
>     datasource-bean-name: <你的DataSource Bean名>
> ```
>
> **如何查找实际的 DataSource Bean 名？**
> - 如果 `application.yml` 配置了 `spring.datasource.name: xxx`，则 Bean 名为 `xxx`
> - 如果未配置 `name`，Spring Boot 默认 Bean 名为 `dataSource`
> - 启动日志中 HikariCP 会打印 DataSource 名称：`HikariDataSource - <名称> - Starting...`
>
> SqlGuard 会拦截所有非 SELECT 语句，复用宿主 DataSource 也是安全的。但仍建议使用只读账号，遵循最小权限原则。

## 第五步：配置安全规则

SnapAgent 的端点需要与宿主应用的安全框架协调。以下是常见场景：

### 场景 A：宿主使用 Spring Security

> **注意**：如果宿主配置了 `server.servlet.context-path`（如 `/rest`），安全白名单中的路径**不含** context-path。Spring Security 的 `antMatchers` 在 context-path 之后匹配。例如 context-path 为 `/rest` 时，SnapAgent 端点实际 URL 为 `/rest/snap-agent/**`，但白名单仍写 `/snap-agent/**`。

```java
@Configuration
public class SecurityConfig extends WebSecurityConfigurerAdapter {
    @Override
    protected void configure(HttpSecurity http) throws Exception {
        http.csrf().disable()
            .authorizeRequests()
                // 公开端点（UI 静态资源）
                .antMatchers("/snap-agent/*.html", "/snap-agent/*.js",
                             "/snap-agent/*.css").permitAll()
                // SnapAgent API 全部 permitAll（鉴权由 SnapAgent 自身完成）
                .antMatchers("/snap-agent/skills").permitAll()        // GET 列表
                .antMatchers("/snap-agent/skills/**").permitAll()     // refresh / upload / delete / upload-folder
                .antMatchers("/snap-agent/models").permitAll()        // GET 模型列表
                .antMatchers("/snap-agent/tools").permitAll()         // GET 工具列表
                .antMatchers("/snap-agent/runs").permitAll()          // POST 创建任务
                .antMatchers("/snap-agent/runs/*").permitAll()        // GET 任务状态
                .antMatchers("/snap-agent/runs/*/stream").permitAll() // GET SSE 流
                .antMatchers("/snap-agent/runs/*/transcript").permitAll() // GET 完整 transcript
                .antMatchers("/snap-agent/conversations").permitAll()       // POST/GET 对话历史
                .antMatchers("/snap-agent/conversations/**").permitAll()    // GET/DELETE 对话详情/下载
                .antMatchers("/snap-agent-internal/**").permitAll()   // Pod 间内部端点
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

SnapAgent 采用**两级 Skill 目录**模型：

| 目录 | 配置项 | 读写性 | 生命周期 | 典型来源 |
|------|--------|--------|----------|----------|
| 内置目录 | `snap-agent.builtin-skills-dir` | 只读 | 随 JAR 打包，重启不变 | 开发期在 `src/main/resources/docs/skills/` 编写 |
| 上传目录 | `snap-agent.upload-skills-dir` | 读写 | 文件系统持久化，重启保留 | 运行期通过 API 上传或运维直接放文件 |

### 内置 Skill（Build-Time）

在 `src/main/resources/docs/skills/` 下创建 `.md` 文件，构建时会被打包进 JAR 的 classpath。该目录只读，运行期无法修改，适合放稳定、随版本发布的 Skill。

### 上传 Skill（Runtime）

运行期可通过 `POST /snap-agent/skills/upload`（单文件）或 `POST /snap-agent/skills/upload-folder`（整目录）上传 Skill 到 `upload-skills-dir`，也可由运维直接在文件系统中放置。该目录可读写，重启后持久化，适合放动态、业务方自助提交的 Skill。

两种 Skill 在 SkillRegistry 中合并注册，LLM 侧无感知差异。如果内置与上传目录存在同名 Skill，**上传目录优先**。

> **内置 Skill 保护机制**：当宿主项目通过 Maven 资源打包将 `docs/skills/` 下的 `.md` 文件加入 classpath 时，`ClasspathSkillScanner` 采用两遍扫描确保 SnapAgent starter JAR 中的内置 skill 始终优先——即使宿主 classpath 中有同名文件，也会被跳过。如需覆盖内置 skill，请通过上传目录或 `POST /skills/upload` API 上传自定义版本。建议宿主打包时用 `<excludes>` 排除内置 skill 同名文件：
> ```xml
> <excludes>
>     <exclude>skills/health-check.md</exclude>
>     <exclude>skills/database-query.md</exclude>
>     <exclude>skills/redis-query.md</exclude>
>     <exclude>skills/log-analysis.md</exclude>
> </excludes>
> ```

下面以内置 Skill 为例，创建 `.md` 文件：

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

内置目录与上传目录**均支持**两种 Skill 形态：单文件 `.md` 和文件夹型 Skill（目录下以 `SKILL.md` 为入口，可携带附加资源文件）。

```
docs/skills/                          # builtin-skills-dir（classpath，只读）
├── order-diagnose.md                 # 单文件 Skill
├── health-check.md
└── replenishment-strategy-diagnose/  # 文件夹 Skill（入口必须为 SKILL.md）
    ├── SKILL.md
    └── reference-data.sql            # 附加资源文件

/tmp/snap-agent-skills/               # upload-skills-dir（文件系统，读写）
├── ad-hoc-check.md                   # 运行期上传的单文件 Skill
└── complex-flow/                     # 运行期上传的文件夹 Skill
    ├── SKILL.md
    └── template.sql
```

## 第七步：启动 & 验证

1. 启动宿主 Spring Boot 应用
2. 访问 `http://localhost:8080/snap-agent/` — 应看到 Chat UI
3. 访问 `http://localhost:8080/snap-agent/skills` — 应返回 Skill 列表 JSON
4. 在 UI 中选择 Skill，输入问题，开始诊断

### CI/CD 可行性验证（推荐在集成时立即执行）

完成集成后，立即验证 CI/CD 环境是否能解析 snap-agent 依赖：

```bash
# 模拟 CI/CD 环境：清除本地缓存后重新解析
mvn dependency:resolve -pl <starter-module> -U
```

如果报 `Could not find artifact cn.watsontech.snapagent:...`，最可能的原因是 Maven `settings.xml` 配置了 `<mirrorOf>*</mirrorOf>`（如 Artifactory/Nexus），将 `file://` 本地仓库请求也拦截了。此时需采用以下方案之一：

1. **推荐**：将 snap-agent JAR 发布到内部 Artifactory/Nexus
2. **备选**：使用 `lib/` 目录 + system scope 方式（见第一步方式 B-fallback）

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

## 代码图谱持久化（集成阶段构建 → 运行时加载）

代码图谱（Code Graph）通过扫描 `.java` 源码构建调用链和影响分析。嵌入式运行时的宿主 JVM **没有源码**，因此图谱必须在**集成阶段（有源码时）构建成 H2 文件**，运行时直接加载。

### 核心闭环

```
功能代码迭代
   │
   ▼
mvn clean package ──► CodeGraphCli 自动重建 H2（绑定 package 阶段，无需手动）
   │                       │
   │                       └─ 含关键业务类完整方法体，排除 target/src-test
   ▼
部署（镜像内携带 codegraph.mv.db）
   │
   ▼
运行时 persistence=h2 直接加载 ──► code_view 工具离线查关键代码定位 bug
```

**数据跟随代码变更**：只要把 `CodeGraphCli` 绑定到 Maven `package`（或 Gradle `build`）阶段，每次构建都会自动重建图谱，代码迭代后无需人工手动更新知识库。

### 构建时机总览

| 方案 | 触发时机 | 适用场景 |
|------|----------|----------|
| **方案一（首选）**：Maven `package` / Gradle `build` 阶段自动重建 | 每次 `mvn clean package` / `./gradlew build` | 功能迭代自动跟随，推荐 |
| **方案二**：CI 流水线预构建 | CI 每次跑流水线时 | 已有 CI/CD 流程，或图谱要跨项目复用 |
| **方案三**：手动运行 CLI | 集成阶段一次性 | 接入验证、本地联调 |

### 背景：memory vs h2

| 模式 | 说明 | 适用场景 |
|------|------|----------|
| `memory`（默认） | 每次启动全量扫描源码，结果存内存 | 本地开发，有源码 |
| `h2` | 持久化到 H2 文件，启动直接加载 | 集成/生产部署，无源码 |

### 方案一（首选）：构建阶段自动重建

> **功能迭代后无需手动重跑 CodeGraphCli**：把 CLI 绑定到构建生命周期，代码一改、一打包，图谱自动重建，数据始终跟随代码变更。

#### Maven（package 阶段）

在宿主 `pom.xml` 中用 `exec-maven-plugin` 调用 `CodeGraphCli`（无需单独的 Maven 插件），绑定到 `package` 阶段。每次 `mvn clean package` 都自动重建图谱：

```xml
<build>
  <plugins>
    <plugin>
      <groupId>org.codehaus.mojo</groupId>
      <artifactId>exec-maven-plugin</artifactId>
      <version>3.1.0</version>
      <executions>
        <execution>
          <id>generate-codegraph</id>
          <phase>package</phase>
          <goals>
            <goal>java</goal>
          </goals>
          <configuration>
            <mainClass>cn.watsontech.snapagent.boot2x.codegraph.CodeGraphCli</mainClass>
            <arguments>
              <argument>--project-root</argument>
              <argument>${project.basedir}</argument>
              <!-- 关键点优先:按 skill 标注的关键类过滤(含完整方法体) -->
              <argument>--scan-mode</argument>
              <argument>skills</argument>
              <argument>--skill-dir</argument>
              <argument>${project.basedir}/docs/skills</argument>
              <argument>--output</argument>
              <argument>${project.build.directory}/data/codegraph</argument>
            </arguments>
          </configuration>
        </execution>
      </executions>
    </plugin>
  </plugins>
</build>
```

然后运行：
```bash
mvn clean package
```

> **注意**：`CodeGraphCli` 会自动排除 `target/`、`src/test/`、`.git/` 等目录，所以绑定在 `package` 阶段不会把编译产物或测试代码扫进图谱。

#### Gradle（build 阶段）

在 `build.gradle` 中添加任务：

```groovy
task generateCodegraph(type: JavaExec) {
    classpath = sourceSets.main.runtimeClasspath
    mainClass = 'cn.watsontech.snapagent.boot2x.codegraph.CodeGraphCli'
    args = [
        '--project-root', project.rootDir.absolutePath,
        '--scan-mode', 'skills',
        '--skill-dir', "${project.rootDir}/docs/skills",
        '--output', "${buildDir}/data/codegraph"
    ]
}

build.dependsOn generateCodegraph
```

然后运行：
```bash
./gradlew build
```

### 方案二：CI 流水线预构建

若已有 CI/CD 流程、或图谱需要在多个下游项目间复用，可在 CI 阶段单独跑一次 `CodeGraphCli`（不依赖 Maven 构建生命周期）。

**GitHub Actions 示例：**

```yaml
- name: Generate CodeGraph
  run: |
    mvn install -DskipTests -pl snap-agent-core,snap-agent-spring-boot-2x-starter
    java -cp "snap-agent-spring-boot-2x-starter/target/*.jar:snap-agent-core/target/*.jar" \
      cn.watsontech.snapagent.boot2x.codegraph.CodeGraphCli \
      --project-root . \
      --scan-mode skills \
      --skill-dir docs/skills \
      --output ./data/codegraph

- name: Upload CodeGraph
  uses: actions/upload-artifact@v3
  with:
    name: codegraph
    path: data/codegraph.mv.db
```

**Jenkins 示例：**

```groovy
stage('Generate CodeGraph') {
    steps {
        sh '''
            mvn install -DskipTests -pl snap-agent-core,snap-agent-spring-boot-2x-starter
            java -cp "snap-agent-spring-boot-2x-starter/target/*.jar:snap-agent-core/target/*.jar" \
              cn.watsontech.snapagent.boot2x.codegraph.CodeGraphCli \
              --project-root . \
              --scan-mode skills \
              --skill-dir docs/skills \
              --output ./data/codegraph
        '''
    }
}
```

> 如果用了方案一（构建阶段自动重建），CI 里通常**不需要**再单独跑这一步 —— 图谱已经随 `mvn clean package` 产出到 `${project.build.directory}/data/codegraph`，直接打镜像即可。

### 方案三：手动运行 CLI（集成验证 / 本地联调）

在集成阶段一次性生成、或本地联调时，直接运行 `CodeGraphCli` 构建 H2 文件：

```bash
# 确保已安装 snap-agent JAR 到本地 Maven 仓库
mvn install -DskipTests -pl snap-agent-core,snap-agent-spring-boot-2x-starter

# 构建 H2 图谱文件（关键点优先：只收 skill 标注的关键业务类）
java -cp "$(mvn dependency:build-classpath -pl snap-agent-spring-boot-2x-starter -q -DincludeScope=runtime -Dmdep.outputFile=/dev/stdout):snap-agent-spring-boot-2x-starter/target/snap-agent-spring-boot-2x-starter-2.0.0-SNAPSHOT.jar" \
  cn.watsontech.snapagent.boot2x.codegraph.CodeGraphCli \
  --project-root . \
  --scan-mode skills \
  --skill-dir docs/skills \
  --output ./data/codegraph
```

> 若要扫描整个包（非关键点优先），把 `--scan-mode skills --skill-dir docs/skills` 换成 `--scan-packages com.yourcompany` 即可。

构建完成后，`./data/codegraph.mv.db` 文件包含关键业务类的完整代码图谱。

#### 参数说明

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `--project-root` | 项目根目录（包含 pom.xml） | 必填 |
| `--scan-mode` | 扫描模式：`all`（全部）/ `skills`（按 skill 标注的关键类）/ `packages`（按包名） | `all` |
| `--skill-dir` | skill `.md` 文件目录（`scan-mode=skills` 时从中提取类名/方法名/表名关键字） | 空 |
| `--scan-packages` | 扫描的包名（逗号分隔，`scan-mode=packages` 时使用） | 扫描所有包 |
| `--output` | 输出路径（不含 .mv.db 后缀） | ./data/codegraph |

> **关键点优先**：嵌入式部署推荐 `--scan-mode skills --skill-dir docs/skills`，只把 skill 文档中标注的关键业务类（含完整方法体）写入 H2，而非全项目。这样 `codegraph.mv.db` 体积可控，运行时经 `code_view` 工具即可离线查看关键业务代码定位 bug。

#### 验证生成结果

```bash
# 检查文件
ls -lh data/codegraph.mv.db

# 查看统计信息（H2 数据库）
java -cp "h2*.jar" org.h2.tools.Shell \
  -url jdbc:h2:file:./data/codegraph \
  -sql "SELECT COUNT(*) FROM CODE_GRAPH_NODES;"
java -cp "h2*.jar" org.h2.tools.Shell \
  -url jdbc:h2:file:./data/codegraph \
  -sql "SELECT COUNT(*) FROM CODE_GRAPH_EDGES;"
```

#### 在 application.yml 中配置

```yaml
snap-agent:
  code-graph:                              # 注意:属性前缀带连字符
    enabled: true
    persistence: h2                        # 使用 H2 持久化（运行时无源码直接加载）
    h2-url: jdbc:h2:file:./data/codegraph  # 指向集成阶段生成的文件
    scan-mode: skills                      # 与构建时一致
    hot-reload-enabled: false              # 生产环境关闭热重载
```

### 将 H2 文件打入 Docker 镜像

若采用**方案一**（package 阶段自动重建），图谱已随 `mvn clean package` 产出到 `${project.build.directory}/data/codegraph`，镜像只需 COPY 即可，无需在 Dockerfile 里再跑 CLI：

```dockerfile
FROM openjdk:8-jre-slim
# 方案一：直接 COPY package 阶段产出的图谱
COPY target/data/codegraph.mv.db /app/data/codegraph.mv.db
COPY target/your-app.jar /app/app.jar
WORKDIR /app
ENTRYPOINT ["java", "-jar", "app.jar"]
```

若必须在镜像内现场构建（例如 Dockerfile 阶段拿不到构建产物），可用多阶段构建调用 CLI：

```dockerfile
# Dockerfile 多阶段构建（方案二/三的替代：镜像内构建）
FROM maven:3.9-openjdk-8 AS codegraph-builder
COPY src/ /app/src/
COPY pom.xml /app/
COPY lib/ /app/lib/   # 如果用 lib/ 本地仓库方式
WORKDIR /app
# 构建图谱（关键点优先）
RUN java -cp "lib/cn/watsontech/snapagent/snap-agent-spring-boot-2x-starter/2.0.0-SNAPSHOT/snap-agent-spring-boot-2x-starter-2.0.0-SNAPSHOT.jar:lib/cn/watsontech/snapagent/snap-agent-core/2.0.0-SNAPSHOT/snap-agent-core-2.0.0-SNAPSHOT.jar:lib/com/h2database/h2/1.4.200/h2-1.4.200.jar" \
  cn.watsontech.snapagent.boot2x.codegraph.CodeGraphCli \
  --project-root /app \
  --scan-mode skills \
  --skill-dir /app/docs/skills \
  --output /app/data/codegraph

FROM openjdk:8-jre-slim
COPY --from=codegraph-builder /app/data/codegraph.mv.db /app/data/codegraph.mv.db
COPY target/your-app.jar /app/app.jar
WORKDIR /app
ENTRYPOINT ["java", "-jar", "app.jar"]
```

> **注意**：H2 文件放在 `/app/data/` 目录下，确保运行时 `h2-url` 指向此路径。如果用了 PVC，可以将 `data/` 挂载到 PVC 上，但通常不需要——图谱是 build-time 产物，不会在运行期变化（除非开启了热重建）。

### 配置 K8s Deployment

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: your-app
spec:
  template:
    spec:
      containers:
        - name: app
          image: your-registry/your-app:latest
          env:
            - name: MY_POD_IP
              valueFrom:
                fieldRef:
                  fieldPath: status.podIP
          volumeMounts:
            - name: codegraph-data
              mountPath: /app/data
      volumes:
        - name: codegraph-data
          emptyDir: {}  # 或者用 PVC 持久化
```

> 如果 H2 文件已打入镜像（推荐），不需要 PVC。`emptyDir` 仅用于运行期 H2 临时写入（如热重建）。

### 配置 application.yml（K8s 部署）

```yaml
snap-agent:
  code-graph:
    enabled: true
    persistence: h2                       # 使用 H2 持久化（运行时无源码直接加载）
    h2-url: jdbc:h2:file:/app/data/codegraph
    scan-mode: skills                     # 与构建时一致（关键点优先）
    hot-reload-enabled: false             # K8s 中无源码，关闭热重建
    hot-reload-poll-ms: 2000
```

### 启动验证清单

启动后检查以下日志确认图谱加载成功：

| 日志关键字 | 含义 | 异常处理 |
|-----------|------|----------|
| `H2 code graph loaded from disk: N nodes` | 成功从 H2 文件加载 | 正常 |
| `H2 code graph DB is empty, triggering initial build...` | H2 文件为空，正在构建 | K8s 中不应出现，说明镜像中 H2 文件缺失 |
| `H2 code graph build complete: N nodes` | 首次构建完成 | K8s 中不应出现（应在集成阶段完成） |
| `H2 code graph build failed: ...` | 构建失败 | 检查 H2 驱动是否在 classpath 中 |
| `CodeGraphHotReloader assembled` | 热重建已启用 | K8s 中应设置 `hot-reload-enabled: false` |

**API 验证：**

```bash
# 验证图谱已加载（应返回非零节点数）
curl -s -u demo:demo http://localhost:8080/snap-agent/api/codegraph/node-count

# 搜索节点（应返回结果）
curl -s -u demo:demo "http://localhost:8080/snap-agent/api/codegraph/search?name=YourService"
```

### 本地开发 vs K8s 部署对比

| 配置项 | 本地开发 | K8s 部署 |
|--------|---------|---------|
| `persistence` | `memory`（默认）或 `h2` | `h2` |
| `h2-url` | `jdbc:h2:file:./data/codegraph` | `jdbc:h2:file:/app/data/codegraph` |
| `scan-mode` | `skills`（关键点优先） | `skills`（与构建时一致） |
| `hot-reload-enabled` | `true`（改代码自动重建） | `false`（无源码） |
| H2 文件来源 | 首次启动自动构建，或方案三手动生成 | 方案一 package 自动重建 / 方案二 CI 预构建，打入镜像 |
| 启动时间 | 首次 ~20s，重启 ~2s（H2） | ~2s（直接加载 H2） |
| H2 驱动 | pom.xml 需添加 `h2` 依赖 | 镜像中需包含 H2 驱动 |

> **H2 驱动依赖**：`snap-agent-spring-boot-2x-starter` 将 H2 声明为 `<optional>true</optional>`，宿主项目必须显式添加：
> ```xml
> <dependency>
>     <groupId>com.h2database</groupId>
>     <artifactId>h2</artifactId>
> </dependency>
> ```

## 自定义工具

使用 `@Tool` 和 `@ToolParam` 注解声明工具方法，注册为 `@Component`，由 `ToolCallbackRegistry` 自动发现：

```java
@Component
public class HttpCallTool {

    @Tool(name = "http_call", description = "Calls an internal API endpoint")
    public ToolResult httpCall(
            @ToolParam(description = "Target URL") String url,
            @ToolParam(description = "HTTP method", required = false) String method) {
        long start = System.currentTimeMillis();
        try {
            // 你的 HTTP 调用逻辑
            String result = doHttpCall(url, method != null ? method : "GET");
            return ToolResult.success(result, 0, System.currentTimeMillis() - start);
        } catch (Exception e) {
            return ToolResult.failure(e.getMessage(), System.currentTimeMillis() - start);
        }
    }
}
```

工具方法会被 `ToolCallbackRegistry` 自动扫描并封装为 `ToolCallback`，暴露给 `ToolsNode` 供 LLM 调用。在 Skill 文件中通过工具名引导 LLM 使用。

## 集成注意事项

### 注意事项 1：响应包装过滤器（Response Wrapping Filters）

如果宿主项目存在**响应包装过滤器**（例如 `BizApiResponseWrapper`，将所有响应统一包装为 `{ "code": 0, "data": ..., "msg": "..." }` 标准信封），**必须**让该过滤器跳过 SSE 流式端点。否则响应会被缓冲，SSE 事件无法实时推送到客户端，导致前端长时间无响应直至超时。

```java
// 在你的响应包装过滤器中，跳过 /stream 路径
if (requestUri != null && requestUri.contains("/stream")) {
    chain.doFilter(request, response);
    return;
}
```

> 除 `/stream` 外，如果包装逻辑会修改 `Content-Type: text/event-stream` 的响应，也建议一并跳过 `transcript` 等长响应端点。

### 注意事项 2：Spring Security 白名单

所有 SnapAgent 端点**必须**加入宿主项目的 Spring Security 白名单（`permitAll`）。SnapAgent 内部完成自身鉴权（Basic Auth / token query param），若被宿主 Security 拦截会导致 401/403 或重定向。

**简洁写法**（推荐）：

```
/snap-agent/**
/snap-agent-internal/**
```

**细粒度写法**：

```
/snap-agent/skills, /snap-agent/skills/**
/snap-agent/models, /snap-agent/tools
/snap-agent/runs, /snap-agent/runs/*, /snap-agent/runs/*/stream, /snap-agent/runs/*/transcript
/snap-agent/conversations, /snap-agent/conversations/**
/snap-agent-internal/**
```

> 详见第五步的安全配置示例。如果宿主使用 Shiro 或无安全框架，对应地在 Shiro 链定义或反向代理层放行这些路径。


### 注意事项 3：SnapAgent 对宿主应用的影响分析

SnapAgent 作为嵌入式库运行在宿主 JVM 内，以下是已识别的影响面及默认安全配置建议：

#### 性能影响

| 影响点 | 说明 | 默认值 | 调优建议 |
|--------|------|--------|----------|
| 线程池 | 共享 Spring 的 `TaskExecutor`，不创建独立线程池 | Spring 默认 | 如需隔离可配置独立 `ThreadPoolTaskExecutor` bean |
| 内存 | Anchor 缓存使用 `ConcurrentHashMap`，maxSize=256/512 | 低占用 | 一般无需调整 |
| 数据库连接 | JDBC 工具复用宿主 `DataSource`，执行 Skill 中定义的 SQL | 0（按需） | 确保只读 DataSource 或配置独立 DataSource |
| **TaskStore 内存** | 每个 task 持有 500 transcript + 1000 audit 条目 | maxSize=5000, ttl=24h | 可按需调低 `snap-agent.agent.task-store-max-size` |
| HTTP 调用 | LLM API 调用使用 OkHttp，同步阻塞 | 超时 120s | 高并发场景注意连接池大小 |

#### 安全影响与默认配置

以下配置建议作为**集成时的默认安全基线**，集成后可按需调整：

1. **URL 白名单最小化**：
   - 生产环境建议使用细粒度白名单（而非 `/snap-agent/**` 全放行）
   - 至少区分静态资源（`*.html, *.js, *.css`）和 API 端点

2. **JWT / Token 鉴权**：
   - SnapAgent 自带 Basic Auth + JWT 鉴权（`snap-agent.security.jwt.secret`）
   - 建议配置 `jwt-secret` 为强随机字符串（至少 32 字符）
   - 生产环境必须修改默认 `jwt-secret`

3. **数据源隔离**：
   - JDBC 工具默认使用宿主主 DataSource，建议配置独立只读 DataSource
   - 配置 `snap-agent.tools.jdbc.datasource-bean-name` 指向只读数据源 bean
   - 避免 Skill 中的 SQL 意外写入或触发事务

4. **Skill 文件管控**：
   - Skill 文件中的 SQL 会直接执行，必须审查 SQL 安全性
   - 建议 Skill 中仅包含 `SELECT` 语句，禁止 `INSERT/UPDATE/DELETE`
   - 使用 `${param}` 参数化查询，禁止拼接 SQL

5. **日志路径**：
   - SnapAgent 默认日志路径为 `/data/logs/`，本地开发需覆盖
   - 生产环境确保日志目录存在且有写入权限

## 常见问题

### Q: 页面打开空白 / JS 报错

浏览器缓存了旧版 JS。硬刷新（`Cmd+Shift+R` / `Ctrl+Shift+R`）或检查 `app.js?v=N` 版本号。

### Q: SSE 流中断

检查服务端日志是否有 `Broken pipe` 或 `Failed write`。通常是客户端关闭连接或网络超时。`snap-agent.llm.timeout-seconds` 控制单个 LLM 请求的超时。

### Q: LLM 返回 401 / 403

检查 `api-key` 或 `auth-token` 配置。如果使用代理，确认 `base-url` 指向正确的代理地址。

### Q: CI/CD 构建报 `Could not find artifact cn.watsontech.snapagent:...`

可能原因有两个，按顺序排查：

1. **多模块路径问题**：`<repository>` 的 URL 用了 `${project.basedir}/lib`，子模块构建时解析为子模块目录而非项目根目录。改为 `${maven.multiModuleProjectDirectory}/lib`。

2. **Mirror 拦截（最可能）**：CI/CD 的 `settings.xml` 配置了 `<mirror>`（如 `<mirrorOf>*</mirror>`），将所有仓库请求重定向到 Artifactory/Nexus，`file://` 本地文件仓库被完全绕过。日志中只有 `Downloading from maven: https://artifactory...` 没有 `Downloading from snap-agent-local:` 即为此问题。此时 `file://` 仓库方案不可用，需改用 **system scope** 方式（见第一步方式 B 的 fallback 说明）。

也要确认 `lib/` 目录和 pom.xml 改动已提交到 Git。

### Q: 数据库查询报错 "SqlGuard rejected"

SqlGuard 拒绝了非 SELECT 语句。检查 Skill 文件中的 SQL 是否只包含 SELECT/SHOW/DESCRIBE/EXPLAIN/WITH 语句。详见 `SqlGuardTest`。

### Q: JDBC 工具（mysql_query）不注册 / Skills 显示 UNAVAILABLE

检查以下三项：

1. **确认 `snap-agent.jdbc.enabled: true`** 已配置
2. **确认 `datasource-bean-name` 与宿主实际 Bean 名一致**：
   ```yaml
   snap-agent:
     jdbc:
       enabled: true
       datasource-bean-name: <实际Bean名>  # 不是固定的 dataSource！
   ```
   查看启动日志 `HikariDataSource - <名称> - Starting...` 获取实际名称。
3. **确认 DataSource 在 Spring 容器中可用**：启动时添加 `--debug` 参数，查看 auto-configuration report 中 `ToolAutoConfiguration#jdbcQueryTools` 的条件匹配结果。

> 从 2.0.0-SNAPSHOT 起，JdbcQueryTools 使用 lazy DataSource 解析，不再因 auto-configuration 顺序问题导致不注册。

### Q: 多实例下任务找不到

跨 Pod 路由未配置或配置错误。检查 `routing.mode` 和 `routing.internal-token`。确认 K8s Service 可以 Pod 间互访。

## 最佳实践

1. **数据库账号用只读**：即使 SqlGuard 拦截写操作，DataSource 也应使用只读数据库账号
2. **内置 Skill 用 Git 管理**：将 `src/main/resources/docs/skills/` 目录纳入版本控制，随代码发布；上传目录的 Skill 建议定期备份或通过 API 导出
3. **LLM 超时设够大**：复杂诊断可能需要多轮 LLM 调用，建议 `timeout-seconds: 120`
4. **限制并发**：生产环境建议 `max-concurrent-runs-per-user: 1`，防止资源滥用
5. **监控线程池**：`snapAgentExecutor` 线程池（core=2, max=4, queue=10），高并发时需调整

---

## 一键集成（推荐）

使用 `snap-agent-integration` skill 可以一句话完成所有集成步骤：

```
用户: 帮我把 /path/to/my-project 集成 SnapAgent
```

**自动执行流程**：

```
Phase 1: 环境探测（只读）
  ├── 扫描项目结构 → Spring Boot 版本、Java 版本、模块列表
  ├── 扫描配置 → 推断 DB、Redis、安全框架
  └── 规模评估 → 决定文档生成策略

Phase 2: 安装 SnapAgent（写入）
  ├── 复制 lib/ (对应版本 JAR)
  ├── 修改 starter 模块 pom.xml
  └── 生成 application-snap.yml

Phase 3: 知识生成
  ├── domain-knowledge-discovery → 领域知识文档
  ├── codegraph 生成 → H2 数据库（CodeGraphCli）
  └── 两轮 Review → 验证完整性

Phase 4: 验证
  ├── mvn clean package → 编译通过
  ├── 启动应用 → Health UP
  └── Chat UI 测试

Phase 5: 输出报告
  └── INTEGRATION_REPORT.md
```

**前提条件**：本地已 `mvn install` SnapAgent 项目（lib/ 已就绪）。

---

## CodeGraph 数据库生成

> **构建方式与配置详见 [代码图谱持久化](#代码图谱持久化集成阶段构建--运行时加载) 章节**。这里只补充 H2 数据库结构与典型产出的参考信息。

### 数据库结构

```sql
-- 节点表（H2，运行时消费格式）
CODE_GRAPH_NODES (
    ID,               -- 全限定类名 / 方法签名
    TYPE,             -- CLASS / METHOD / FIELD
    NAME,             -- 简短名称
    PACKAGE,          -- 包名
    CLASS_NAME,       -- 所属类
    RETURN_TYPE,      -- 返回类型（方法节点）
    FILE_PATH,        -- 源文件路径
    LINE_NUMBER,      -- 行号
    SOURCE_CODE       -- 关键类完整源码（含完整方法体，运行时 code_view 离线诊断用）
)

-- 边表
CODE_GRAPH_EDGES (
    FROM_ID,          -- 源节点
    TO_ID,            -- 目标节点
    EDGE_TYPE,        -- CALLS / EXTENDS / IMPLEMENTS / DEPENDS_ON / REFERENCES
    CONTEXT           -- 位置信息
)
```

> `SOURCE_CODE` 列由 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` 自动迁移，旧库升级无需重建。

### 典型产出（scan-mode=skills 关键点优先）

| 项目规模 | 关键类数 | 节点数 | 数据库大小 | 源码存储 |
|----------|----------|--------|-----------|----------|
| 小型（<10 关键类） | ~10 | ~50 | ~200KB | 含完整方法体 |
| 中型（10-50 关键类） | ~30 | ~150 | ~1MB | 含完整方法体 |
| 大型（>50 关键类） | ~50+ | ~300+ | ~3MB | 含完整方法体 |

> 体积远小于全量扫描（全量扫描会把所有类都纳入，动辄 10MB~500MB）。关键点优先只收 skill 标注的关键业务类，运行时 `code_view` 工具离线查看关键代码定位 bug。

---

## 集成检查清单

完成集成后，逐项验证：

- [ ] `mvn clean package -DskipTests` 编译通过
- [ ] Fat JAR 中包含 `snap-agent-core` 和 `snap-agent-spring-boot-2x-starter`
- [ ] `/snap-agent/health` 返回 `{"status":"UP"}`
- [ ] Chat UI (`/snap-agent/chat/index.html`) 可访问
- [ ] Skills 列表 API 返回至少 1 个 Skill
- [ ] LLM 对话正常（发送消息 → 收到回复）
- [ ] CodeGraph 数据库已生成（`data/codegraph.mv.db`）
- [ ] 知识库文档已生成（`src/main/resources/docs/knowledge/*.md`）
- [ ] Settings 页面 (`/snap-agent/settings.html`) 可访问

## 故障排查

| 问题 | 排查 |
|------|------|
| 编译失败：找不到 snap-agent | 检查 lib/ 目录和 pom.xml systemPath |
| 启动失败：Bean 冲突 | 检查 `@ConditionalOnMissingBean` 配置 |
| LLM 连接失败 | 检查 `LLM_AUTH_TOKEN` 环境变量 |
| Chat UI 404 | 检查 `snap-agent.base-path` 配置 |
| CodeGraph 为空 | 检查 `snap-agent.code-graph.scan-packages` 包名 |
| 知识库未加载 | 检查文件是否在 `classpath:/docs/knowledge/` |

