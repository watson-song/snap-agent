# 知识库生成优化建议

## 1. 问题分析

### 1.1 为什么 Memory 系统被遗漏？

**根本原因**：`domain-knowledge-discovery` skill 的设计目标是**业务领域知识发现**，而非**技术架构知识发现**。

**扫描范围**：
- ✅ Controller / Service / Mapper（业务层）
- ✅ Entity / DTO / VO（数据层）
- ✅ 表结构、多租户、字段分析
- ❌ Memory 系统（基础设施层）
- ❌ Agent 引擎（核心 SPI）
- ❌ Graph 框架（执行引擎）
- ❌ Advisor 系统（增强机制）

### 1.2 遗漏的核心组件

| 组件 | 重要性 | 当前状态 | 影响 |
|------|--------|----------|------|
| Memory 系统 | ⭐⭐⭐⭐⭐ | ❌ 未生成 | Agent 无法理解对话记忆机制 |
| Agent 引擎 | ⭐⭐⭐⭐⭐ | ️ 部分覆盖 | 缺少 ReAct Graph 详细流程 |
| Advisor 系统 | ⭐⭐⭐⭐ | ❌ 未生成 | 无法理解上下文增强机制 |
| Tool 系统 | ⭐⭐⭐⭐ | ️ 部分覆盖 | 缺少工具注册和发现流程 |
| Graph 框架 | ⭐⭐⭐ | ❌ 未生成 | 无法理解节点执行流程 |

## 2. 优化方案

### 2.1 短期方案：手动补充技术架构知识

**优先级**：P0（立即执行）

创建以下知识文件：

| 文件 | 内容 | 状态 |
|------|------|------|
| `snap-agent-memory-system.md` | Memory 组装、ChatMemory、Advisor | ✅ 已完成 |
| `snap-agent-agent-engine.md` | ReAct Graph、Node 执行、状态管理 | 🔄 待补充 |
| `snap-agent-advisor-system.md` | Advisor SPI、Order 排序、注入流程 | 🔄 待补充 |
| `snap-agent-tool-system.md` | Tool SPI、注册、发现、执行 | 🔄 待补充 |
| `snap-agent-graph-framework.md` | GraphState、Checkpoint、Interrupt | 🔄 待补充 |

### 2.2 长期方案：扩展 domain-knowledge-discovery skill

**优先级**：P1（下一迭代）

#### 2.2.1 新增"技术组件发现"能力

```markdown
## Step X: 技术基础设施扫描

扫描目标：
1. 识别核心 SPI 接口（如 LlmClient、ChatMemory、Advisor）
2. 识别实现类及其关系（继承、实现、依赖）
3. 识别自动配置类（@Configuration、@Bean）
4. 识别配置属性（@ConfigurationProperties）

提取规则：
1. 接口 + 实现类 → SPI 设计模式
2. @Bean 方法 → 组件组装流程
3. @ConditionalOnMissingBean → 可扩展点
4. @ConfigurationProperties → 配置项
```

#### 2.2.2 新增"架构分层"分析

```markdown
## Step Y: 架构分层识别

分层模型：
1. Web 层（Controller、REST API）
2. Agent 层（AgentService、ReActGraph）
3. SPI 层（LlmClient、Skill、Tool）
4. 基础设施层（Bridge、Memory、Security）
5. 持久化层（Repository、Store）

提取规则：
1. 包名 → 层级（web/、agent/、core/、boot2x/）
2. 依赖方向 → 分层验证
3. 接口位置 → SPI 边界
```

#### 2.2.3 新增"组件关系图"生成

```markdown
## Step Z: 组件依赖关系

提取规则：
1. @Autowired → 依赖关系
2. implements → 实现关系
3. extends → 继承关系
4. @Import → 配置导入

输出格式：
- 组件依赖图（Mermaid）
- 组装流程图（Sequence Diagram）
- 分层架构图（Layer Diagram）
```

### 2.3 创建新 skill：technical-architecture-discovery

**适用场景**：
- 技术文档生成
- 架构审查
- 新人 onboarding

**工作流程**：
```
1. 扫描项目结构（包、模块）
2. 识别核心 SPI 接口
3. 分析实现类和依赖关系
4. 提取自动配置逻辑
5. 生成架构图和文档
```

## 3. 执行计划

### Phase 1: 手动补充（本周）
- [x] Memory 系统知识
- [ ] Agent 引擎知识
- [ ] Advisor 系统知识
- [ ] Tool 系统知识
- [ ] Graph 框架知识

### Phase 2: Skill 优化（下周）
- [ ] 扩展 domain-knowledge-discovery skill
- [ ] 添加技术组件发现步骤
- [ ] 添加架构分层分析
- [ ] 添加组件关系图生成

### Phase 3: 新 Skill 开发（下下周）
- [ ] 创建 technical-architecture-discovery skill
- [ ] 编写测试用例
- [ ] 文档和示例

## 4. 验证标准

### 4.1 完整性验证

运行以下查询验证知识库覆盖度：

| 查询 | 期望结果 | 状态 |
|------|----------|------|
| "SnapAgent 如何组装 memory？" | 详细架构图 + 代码流程 | ✅ |
| "Agent 引擎如何执行任务？" | ReAct Graph 流程 | 🔄 |
| "Advisor 如何注入上下文？" | Order 排序 + beforeNode/afterNode |  |
| "Tool 如何注册和发现？" | ToolCallbackRegistry 流程 | 🔄 |
| "Graph 状态如何管理？" | GraphState + Checkpoint | 🔄 |

### 4.2 准确性验证

- 代码引用准确（类名、方法名、配置项）
- 流程图符合实际执行顺序
- 配置示例可直接使用

## 5. 总结

**核心问题**：知识库生成逻辑缺少技术架构发现能力

**解决方案**：
1. 短期：手动补充 5 个核心技术知识文件
2. 长期：扩展 skill + 创建新 skill

**预期效果**：
- 知识库覆盖率从 40% → 90%+
- 技术查询准确率从 30% → 85%+
- 新人 onboarding 时间减少 50%
