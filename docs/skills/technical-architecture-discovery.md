---
name: technical-architecture-discovery
description: 集成阶段技术架构知识生成工具 — 扫描 SPI 接口、自动配置、组件关系，生成技术架构知识库文档
version: 2.0.0
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
> **职责**：生成技术基础设施层知识（SPI 接口、AutoConfig、Advisor、组件关系）。
> **与 domain-knowledge-discovery 的关系**：本工具负责"技术架构层"，domain-knowledge-discovery 负责"业务领域层"，两者互补不重叠。
> **产出**：`{output-dir}/*.md`，部署时打包进 `classpath:/docs/knowledge/`。

你是一个技术架构分析师。你的任务是从宿主项目源码中提取技术基础设施组件，生成结构化的技术架构文档。

## 核心规则（最高优先级）

1. **所有代码内容必须来自实际源文件** — 直接读取项目源码，严禁编造任何类名、方法名、接口签名
2. **读不到就标记"待补充"** — 绝不为了填满模板而编造内容
3. **动态输出 section** — 没有的内容直接省略对应 section，不用占位
4. **扫描目标 = 宿主项目** — 排除 SnapAgent 自身的包（`cn.watsontech.snapagent.*`），除非宿主项目本身就是 SnapAgent

## 输入参数

| 参数 | 必填 | 说明 | 示例 |
|------|------|------|------|
| `project_root` | 是 | 项目根目录绝对路径 | `/Users/xxx/my-project` |
| `output_dir` | 是 | 知识文件输出目录 | `src/main/resources/docs/knowledge` |
| `scan_packages` | 否 | 限定扫描的包前缀 | `com.example` |

## 工作流程

### Step 1: 项目分层识别

读取项目根目录，识别模块分层：

```
扫描步骤：
1. 读取 pom.xml <modules> → 模块列表
2. 识别模块职责：
   - 含 core/common 的模块 → SPI 接口层
   - 含 starter/boot 的模块 → 自动配置层
   - 含 web/api 的模块 → Web 层
   - 含 demo/standalone 的模块 → 示例/部署层
3. 识别核心包路径：
   - *.core.* 或 *.spi.* → 核心 SPI
   - *.autoconfig.* → 自动配置
   - *.web.* 或 *.controller.* → Web 层
   - *.advisor.* → Advisor 增强
```

### Step 2: SPI 接口提取

扫描核心模块，提取所有公共接口和抽象类：

```
提取规则（必须从源码读取）：
1. public interface → SPI 接口定义
   - 读取完整接口源码 → 提取方法签名 + Javadoc
   - 查找 implements → 识别实现类
2. abstract class → 抽象基类
   - 读取 abstract 方法 → 提取扩展点
3. 工厂类（*Factory）
   - 读取 build/create 方法 → 提取构建流程
```

**输出格式**：
```markdown
## SPI: XxxInterface

\```java
// 从源码中提取的真实接口定义
public interface XxxInterface {
    /** Javadoc 原文 */
    ReturnType methodName(ParamType param);
}
\```

**实现类**（从源码 implements 关系提取）：
| 类名 | 模块 | 说明 |
|------|------|------|
| DefaultXxxImpl | core | 默认实现 |
```

### Step 3: 自动配置分析

扫描 starter 模块，提取 @Configuration 和 @Bean 方法：

```
提取规则（必须从源码读取）：
1. @Configuration 类 → 配置模块
   - @ConditionalOnProperty → 启用条件
   - @ConditionalOnClass → 类路径条件
   - @Import → 依赖的其他配置
2. @Bean 方法 → 组件列表
   - 方法签名 → Bean 类型和名称
   - 参数列表 → 依赖注入关系
   - @ConditionalOnMissingBean → 可扩展点标记
3. @ConfigurationProperties → 配置项
   - prefix → 配置前缀
   - 字段名 + 默认值 → 完整配置树
```

**输出格式**：
```markdown
## 自动配置: XxxAutoConfiguration

**启用条件**: `snap-agent.xxx.enabled=true`

**Bean 列表**:
| Bean | 类型 | 可扩展 | 依赖 |
|------|------|--------|------|
| xxxService | XxxService | ✅ @ConditionalOnMissingBean | XxxRepository |

**配置属性**:
\```yaml
snap-agent:
  xxx:
    enabled: true
    property1: defaultValue  # 说明
\```
```

### Step 4: Advisor 链提取

扫描所有实现 Advisor 接口的类：

```
提取规则（必须从源码读取）：
1. implements Advisor → Advisor 实现类
2. getOrder() → 执行顺序
3. beforeNode/afterNode → 增强逻辑
4. 注入的依赖 → 使用的 SPI
```

### Step 5: 组件依赖关系

从 @Autowired 和构造器参数提取组件间依赖：

```
提取规则：
1. 构造器注入 → 强依赖（实线）
2. @Autowired → 可选依赖（虚线）
3. ObjectProvider → 延迟依赖（虚线+标注）
```

输出 Mermaid 组件图。

### Step 6: 生成技术知识文件

按子系统/模块生成知识文件，每个文件聚焦一个技术子系统：

**文件命名**：`{module}-{subsystem}.md`（如 `myapp-auth-system.md`）

**动态模板**（只输出实际存在的 section）：

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
[从包结构和依赖关系推断的架构图]

## 核心 SPI
[接口定义 + 实现类，从源码提取]

## 自动配置
[@Bean 组装流程，从源码提取]

## 组件关系
[Mermaid 依赖图]

## 配置属性
[完整配置树，从 @ConfigurationProperties 提取]

## 扩展点
[@ConditionalOnMissingBean 列表]
```

### Step 7: 自检验证

```
验证清单：
1. 每个接口方法签名 → 与源码一致
2. 每个 @Bean 方法 → 在 AutoConfig 类中存在
3. 每个配置属性 → 在 Properties 类中定义
4. 每个 Advisor 的 Order 值 → 与 getOrder() 一致
5. Mermaid 图中的类名 → 全部在源码中存在
6. 没有编造的类名、方法名、配置项

不通过则修正后重新输出。
```

## 输出规范

- 文件名 kebab-case
- YAML frontmatter 必须包含
- 代码块内容必须来自实际源码（可精简，不可编造）
- 每个文件 100-200 行，不超过 300 行

## 与 domain-knowledge-discovery 的分工

| 维度 | domain-knowledge-discovery | technical-architecture-discovery |
|------|---------------------------|--------------------------------|
| 关注层 | 业务领域层 | 技术基础设施层 |
| 扫描目标 | Service/Controller/Entity/Mapper | SPI/AutoConfig/Advisor/Factory |
| 提取内容 | 业务概念、表结构、数据流向 | 接口定义、Bean 组装、配置属性 |
| 输出文件 | `allocation-plan.md`、`order-create.md` | `myapp-auth-system.md`、`myapp-cache-layer.md` |

## 版本历史

- **v2.0.0**：重定位为集成阶段工具，移除 agent tools 依赖，明确与 domain-knowledge-discovery 的分工，添加强制源码提取约束和自检验证
- **v1.0.0**：初始版本
