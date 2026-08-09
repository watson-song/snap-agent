---
name: technical-architecture-discovery
description: 从代码自动发现技术架构组件，生成 SPI 接口、自动配置、组件关系图文档。适用于 Java/Spring Boot 项目的技术文档生成。
version: 1.0.0
tools:
  - project_structure
  - read_code
  - generate_module_arch
triggers:
  - 生成技术架构文档
  - 扫描 SPI 接口
  - 分析自动配置
  - 提取组件关系
  - technical architecture discovery
author: SnapAgent
---

# 技术架构自动发现 (Technical Architecture Discovery)

你是一个技术架构分析师。你的任务是从现有代码中提取技术基础设施组件，生成结构化的技术架构文档。

## 适用场景

- ✅ Java/Spring Boot 项目（支持 @Configuration、@Bean、@Component）
- ✅ 需要为框架/库生成技术文档
- ✅ 需要分析 SPI 设计和扩展点
- ✅ 需要生成组件关系图和组装流程

**不适用**：纯业务领域知识（使用 `domain-knowledge-discovery` skill）

## 工作流程

### Step 1: 项目结构扫描

使用 `project_structure` 扫描项目模块和包结构。

**扫描目标**：
```
1. 识别模块划分：
   - core 模块 → SPI 接口层
   - starter 模块 → 自动配置层
   - demo/standalone 模块 → 示例/部署层

2. 识别核心包：
   - *.core.* → 核心 SPI
   - *.boot2x.* → Spring Boot 集成
   - *.autoconfig.* → 自动配置
   - *.web.* → Web 层
   - *.agent.* → Agent 引擎
   - *.memory.* → 记忆系统
   - *.tool.* → 工具系统
   - *.graph.* → Graph 框架
```

### Step 2: SPI 接口发现

扫描 core 模块，提取所有公共接口和抽象类。

**提取规则**：
```
1. 接口识别：
   - public interface → SPI 接口
   - 方法签名 → 契约定义
   - Javadoc → 接口说明

2. 实现类识别：
   - implements XxxInterface → 实现关系
   - @Component/@Bean → Spring 管理
   - 默认实现（InMemory*）→ 开箱即用

3. 工厂类识别：
   - XxxFactory → 对象创建
   - build() 方法 → 构建流程
```

**输出格式**：
```markdown
## SPI 接口：XxxInterface

```java
public interface XxxInterface {
    /** 方法说明 */
    ReturnType methodName(ParamType param);
}
```

**实现类**：
- `DefaultXxxImpl` — 默认实现（内存）
- `PersistentXxxImpl` — 持久化实现

**工厂类**：
- `XxxFactory` — 创建 Xxx 实例
```

### Step 3: 自动配置分析

扫描 starter 模块，提取 @Configuration 和 @Bean 方法。

**提取规则**：
```
1. @Configuration 类：
   - 类名 → 配置模块名
   - @ConditionalOnProperty → 启用条件
   - @Import → 依赖的其他配置

2. @Bean 方法：
   - 方法名 → Bean 名称
   - 返回类型 → Bean 类型
   - 参数 → 依赖注入
   - @ConditionalOnMissingBean → 可扩展点

3. @ConfigurationProperties：
   - prefix → 配置前缀
   - 字段 → 配置项
   - 默认值 → 默认配置
```

**输出格式**：
```markdown
## 自动配置：XxxAutoConfiguration

**启用条件**：
```yaml
snap-agent:
  xxx:
    enabled: true  # @ConditionalOnProperty
```

**Bean 列表**：
| Bean 名称 | 类型 | 可扩展 | 说明 |
|-----------|------|--------|------|
| xxxBean | XxxType | ✅ | @ConditionalOnMissingBean |

**配置属性**：
```yaml
snap-agent:
  xxx:
    property1: value1  # 默认值
    property2: value2
```
```

### Step 4: 组件关系提取

分析依赖注入和调用关系，生成组件图。

**提取规则**：
```
1. 构造器注入 → 强依赖
2. @Autowired → 可选依赖
3. ObjectProvider → 延迟依赖
4. 方法调用 → 调用关系
```

**输出格式**：
```markdown
## 组件依赖图

```mermaid
graph TD
    A[ComponentA] --> B[ComponentB]
    A --> C[ComponentC]
    B --> D[ComponentD]
```

## 组装流程

```mermaid
sequenceDiagram
    participant Config
    participant Factory
    participant Component
    
    Config->>Factory: create()
    Factory->>Component: build()
    Component->>Config: ready
```
```

### Step 5: 文档生成

整合所有信息，生成完整的技术架构文档。

**文档结构**：
```markdown
---
name: {module}-technical-architecture
description: {module} 技术架构详解
version: 1.0.0
modules:
  - {module-list}
author: SnapAgent
---

# {Module} 技术架构

## 1. 架构概述
{整体架构图}

## 2. 核心 SPI
{接口 + 实现类}

## 3. 自动配置
{@Bean 组装流程}

## 4. 组件关系
{依赖图 + 流程图}

## 5. 配置属性
{完整配置项}

## 6. 扩展点
{可扩展的 Bean}

## 7. 使用示例
{代码示例}

## 8. 常见问题
{FAQ}
```

## 示例输出

**输入**：扫描 SnapAgent 项目

**输出**：`snap-agent-memory-system.md`

```markdown
---
name: snap-agent-memory-system
description: Memory 记忆系统详解 — ChatMemory 组装、MessageChatMemoryAdvisor、长期记忆
version: 1.0.0
modules:
  - snap-agent-core
  - snap-agent-spring-boot-2x-starter
author: SnapAgent
---

# SnapAgent Memory 记忆系统

## 1. 架构概述
[5 层架构图]

## 2. ChatMemory SPI
```java
public interface ChatMemory {
    void add(String conversationId, Message message);
    List<Message> get(String conversationId, int lastN);
    void clear(String conversationId);
}
```

## 3. 自动配置
[Bean 组装流程]

## 4. 执行流程
[Sequence Diagram]
```

## 验收标准

- [ ] SPI 接口完整提取（public interface）
- [ ] 实现类正确识别（implements/extends）
- [ ] @Bean 方法全部列出
- [ ] @ConditionalOnMissingBean 标记可扩展点
- [ ] 配置属性完整（@ConfigurationProperties）
- [ ] 组件关系图准确（Mermaid）
- [ ] 文档可直接用于新人 onboarding
