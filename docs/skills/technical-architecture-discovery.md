---
name: technical-architecture-discovery
description: 集成阶段技术架构知识生成工具 — 扫描 SPI 接口、自动配置、组件关系，生成技术架构知识库文档
version: 3.0.0
type: integration-tool
triggers:
  - 生成技术架构文档
  - 扫描 SPI 接口
  - 分析自动配置
  - 提取组件关系
  - technical architecture discovery
author: SnapAgent
---

# 技术架构自动发现 (Technical Architecture Discovery)

> **定位**：集成阶段工具，在宿主项目本地执行，直接读取源码文件系统。
> **不是**运行时 Agent Skill，不参与 Agent 执行循环。
> **适用**：任何 Java/Spring Boot 项目（尤其是框架/库类项目）。
> **职责**：生成技术基础设施层知识（SPI 接口、AutoConfig、组件关系）。
> **与 domain-knowledge-discovery 的关系**：本工具负责"技术架构层"，domain-knowledge-discovery 负责"业务领域层"，两者互补不重叠。
> **产出**：`{output-dir}/*.md`，部署时打包进 `classpath:/docs/knowledge/`。

你是一个技术架构分析师。你的任务是从宿主项目源码中提取技术基础设施组件，生成结构化的技术架构文档。

## 核心规则（最高优先级，违反任何一条 = 输出不合格）

1. **逐字引用，严禁重写** — 每个接口定义、方法签名必须从源文件逐字复制，不允许凭记忆/推测重写。每个代码块必须标注来源：`<!-- source: path/to/File.java -->`
2. **方法数必须一致** — 输出某接口的方法列表前，先数源文件中有多少个 public/abstract/default 方法，确认文档中列出的数量一致。少一个 = 不合格
3. **模块归属从路径推断** — 类的模块归属必须看其文件路径（如 `xxx-core/src/` → core 模块），不允许猜测
4. **动态 section** — 没有的内容直接省略，不用占位
5. **版本一致性** — 如果同一职责有新旧两套实现，以 `@Bean` 中实际装配的为准；`@Deprecated` 类标注但不跳过
6. **注解逐字提取** — `@ConditionalOnProperty`、`@ConditionalOnMissingBean` 等条件注解必须从源文件逐字复制，不允许简化或推测

## 输入参数

| 参数 | 必填 | 说明 | 示例 |
|------|------|------|------|
| `project_root` | 是 | 项目根目录绝对路径 | `/Users/xxx/my-project` |
| `output_dir` | 是 | 知识文件输出目录 | `src/main/resources/docs/knowledge` |
| `scan_packages` | 否 | 限定扫描的包前缀 | `com.example` |

## 执行流程

> **关键**：分两个阶段执行。阶段 1 只读源码、整理中间表，不生成文档。阶段 2 基于中间表生成文档，不再回读源码。

### ═══ 阶段 1：信息收集（只读不写）═══

#### Step 1: 项目分层识别

读取项目根目录：

```
1. pom.xml / build.gradle → 模块列表
2. 识别模块职责：
   - 含 core/common/spi 的模块 → SPI 接口层
   - 含 starter/boot/autoconfig 的模块 → 自动配置层
   - 含 web/api/controller 的模块 → Web 层
   - 含 demo/standalone 的模块 → 示例/部署层
3. 识别核心包路径（从实际目录结构，不猜测）
```

#### Step 2: SPI 接口提取

扫描核心模块，对每个 public interface / abstract class，**打开源文件**，提取并记录到中间表：

```
中间表格式：
| 文件路径 | 接口名 | 模块 | 方法数 | 完整方法签名列表 | Javadoc摘要 |
|----------|--------|------|--------|----------------|------------|
| core/.../XxxService.java | XxxService | core | 5 | void doA(String) / String getB(int) / ... | 描述 |
```

提取规则：
- 读取完整接口源码 → 逐字复制所有方法签名（含参数类型、返回类型、default 方法）
- 查找 `implements XxxInterface` → 识别实现类（记录文件路径+模块）
- 查找 `extends XxxClass` → 识别继承关系
- 查找 `*Factory` 类 → 提取 build/create 方法

#### Step 3: 自动配置分析

扫描自动配置模块，对每个 `@Configuration` 类，**打开源文件**，提取：

```
中间表格式：
| 文件路径 | 配置类名 | 顶层条件 | @Bean方法列表 | @ConditionalOnMissingBean | 配置属性类 |
```

提取规则：
- 类级 `@Conditional*` 注解 → **逐字复制**
- 每个 `@Bean` 方法：方法签名 + 返回类型 + `@ConditionalOnMissingBean` + 参数列表（= 依赖注入）
- `@ConfigurationProperties` 类：prefix + 每个字段名 + 类型 + 默认值

#### Step 4: 组件依赖关系

从构造器参数和 `@Autowired` 字段提取依赖关系，记录到中间表：

```
| 源类 | 依赖类 | 注入方式 | 说明 |
|------|--------|---------|------|
| AgentService | LlmClient | 构造器 | 强依赖 |
| AgentService | List<Advisor> | ObjectProvider | 可选 |
```

### ═══ 阶段 2：文档生成（只写不读）═══

基于中间表生成文档。中间表中没有的数据，对应 section 不输出。

#### Step 5: 动态 section 规则

| Section | 输出条件 | 跳过条件 |
|---------|---------|---------|
| 架构概述 | 始终输出 | — |
| 核心 SPI | 中间表有接口 | 无 SPI 接口 |
| 自动配置 | 中间表有 @Configuration | 无自动配置 |
| Advisor 链 | 中间表有 Advisor 实现 | 无 Advisor |
| 组件关系 | 中间表有依赖关系 | — |
| 配置属性 | 中间表有 @ConfigurationProperties | 无配置属性 |
| 扩展点 | 中间表有 @ConditionalOnMissingBean | 无扩展点 |

#### Step 6: 生成技术知识文件

**文件命名**：`{module}-{subsystem}.md`（如 `myapp-auth-system.md`）

**模板**：

```markdown
---
name: 子系统名称
description: 子系统描述
version: 1.0.0
modules: [模块列表]
author: technical-architecture-discovery
---

# 子系统名称

## 架构概述
[从包结构和依赖关系推断]

## 核心 SPI
\```java
// <!-- source: path/to/XxxInterface.java -->
public interface XxxInterface {
    ReturnType method1(ParamType param);  // 逐字复制
    ReturnType method2(ParamType param);  // 逐字复制
}
\```

| 实现类 | 模块 | 说明 |
|--------|------|------|
| DefaultImpl | core | 默认实现 |

## 自动配置
**启用条件**: `@ConditionalOnProperty(prefix="xxx.yyy", name="enabled", havingValue="true")`  ← 逐字复制

| Bean | 类型 | 可扩展 | 依赖 |
|------|------|--------|------|
| xxxService | XxxService | ✅ | XxxRepository |

## 配置属性
\```yaml
xxx:
  yyy:
    enabled: true      # 来自 Properties 类字段
    property1: default  # 说明
\```
```

#### Step 7: 自检验证（方法级）

对生成的每个文件，逐项验证：

```
验证清单：
1. 来源标注 — 每个代码块有 <!-- source: ... --> 且路径真实存在？
2. 方法完整性 — 源文件中 public/abstract/default 方法数 = 文档中列出的方法数？
3. 签名精确性 — 每个方法的参数数量、参数类型、返回类型 = 源文件一致？
4. 模块归属 — 每个类的模块 = 其文件路径所在模块？
5. 注解精确性 — @Conditional* 注解 = 源文件逐字复制？
6. @Bean 完整性 — AutoConfig 类中 @Bean 方法数 = 文档中列出的 Bean 数？
7. 配置属性完整性 — Properties 类中字段数 = 文档中列出的配置项数？
8. 无虚构 — 没有中间表中不存在的类名/方法名/配置项？

输出验证报告：
| 检查项 | 通过 | 失败详情 |
|--------|------|---------|

任何一项不通过 → 回到阶段 1 重新读取对应源文件。
```

## 输出规范

- 文件名 kebab-case
- YAML frontmatter 必须包含
- 代码块必须标注 `<!-- source: path/to/File.java -->`
- 可精简（省略 private 方法等），不可编造
- 每个文件 100-200 行，不超过 300 行

## 与 domain-knowledge-discovery 的分工

| 维度 | domain-knowledge-discovery | technical-architecture-discovery |
|------|---------------------------|--------------------------------|
| 关注层 | 业务领域层 | 技术基础设施层 |
| 扫描目标 | Service/Controller/Entity/Mapper | SPI/AutoConfig/Advisor/Factory |
| 提取内容 | 业务概念、表结构、数据流向 | 接口定义、Bean 组装、配置属性 |
| 输出文件 | `order-management.md`、`inventory.md` | `myapp-auth-system.md`、`myapp-cache-layer.md` |

## 版本历史

- **v3.0.0**：添加两阶段执行（收集→生成）、方法级自检、source 标注、版本一致性检查、动态 section 规则表、注解逐字提取规则
- **v2.0.0**：重定位为集成阶段工具，移除 agent tools 依赖，明确分工
- **v1.0.0**：初始版本
