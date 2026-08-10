# 知识库生成优化计划

> 最后更新：2026-08-10，配合 domain-knowledge-discovery / technical-architecture-discovery 重定位

## 1. 问题回顾

### 1.1 原始问题

知识库 21 个文件存在大量 LLM 编造的代码示例，根因是两个知识生成 skill 设计缺陷：

| 缺陷 | 说明 |
|------|------|
| tools 声明错误 | 声明了不存在的 `read_code`、`generate_module_arch`，实际工具是 `code_read` |
| 无强制源码提取约束 | LLM 读不到源码时编造代码示例填满模板 |
| 固定模板 | 强迫输出不存在的 section（如 SQL 性能分析），LLM 为填满而编造 |
| 缺少自检验证 | 生成后没有步骤验证类名/配置项/方法签名是否真实存在 |
| 两个 skill 职责重叠 | domain-knowledge-discovery 的 Step X/Y/Z 与 technical-architecture-discovery 完全重复 |

### 1.2 已采取的修复

2026-08-10 完成 skill 重定位：

| 改动 | 说明 |
|------|------|
| 从 starter 移除 | 不再打包为内置 skill，移出 `snap-agent-spring-boot-2x-starter/src/main/resources/docs/skills/` |
| 移至 `docs/skills/` | 作为集成阶段开发工具文档，位于项目根目录 `docs/skills/` |
| 重定位为集成工具 | 添加 `type: integration-tool` 标记，明确不是运行时 Agent Skill |
| 移除 tools 声明 | 集成阶段直接读取文件系统，不依赖 agent tools |
| 添加强制约束 | "所有代码内容必须来自实际源文件，严禁编造" |
| 动态模板 | 没有的 section 直接省略，不用占位 |
| 添加自检验证 | 生成后验证类名/方法签名/配置项 |
| 消除重叠 | Step X/Y/Z 从 domain skill 移到 tech skill，职责清晰分离 |

## 2. 两个 skill 的当前定位

| 维度 | domain-knowledge-discovery | technical-architecture-discovery |
|------|---------------------------|--------------------------------|
| 关注层 | 业务领域层 | 技术基础设施层 |
| 扫描目标 | Service/Controller/Entity/Mapper | SPI/AutoConfig/Advisor/Factory |
| 提取内容 | 业务概念、表结构、数据流向 | 接口定义、Bean 组装、配置属性 |
| 输出文件 | `allocation-plan.md`、`order-create.md` | `myapp-auth-system.md`、`myapp-cache-layer.md` |
| 运行时机 | 集成阶段，本地执行 | 集成阶段，本地执行 |
| 文件位置 | `docs/skills/domain-knowledge-discovery.md` | `docs/skills/technical-architecture-discovery.md` |

## 3. 待优化项

### 3.1 知识库文件质量修复（P0）

现有 21 个知识文件含大量虚构代码，需重新生成或手动修正：

| 文件 | 状态 | 处理 |
|------|------|------|
| `snap-agent-graph-framework.md` | ❌ 含虚构 ParallelNode/StateCompressor | 用真实代码重写 |
| `snap-agent-tool-system.md` | ❌ 含虚构 MySqlQueryTool/PluginToolCallbackRegistry | 用真实代码重写 |
| `snap-agent-advisor-system.md` | ❌ 含虚构 RAGAdvisor/CostTrackingAdvisor/AnchorOrchestrator | 用真实代码重写 |
| `snap-agent-security-system.md` | ❌ 含虚构 JwtTokenProvider/JwtAuthenticationFilter | 删除虚构 section |
| `snap-agent-embedding.md` | ❌ 含虚构 OpenAiEmbeddingModel/OllamaEmbeddingModel | 用真实代码重写 |
| `snap-agent-architecture.md` | ⚠️ AutoConfig 类不全、职责不准 | 补充缺失 6 个类 |
| `snap-agent-cost-tracking.md` | ⚠️ 类名错误(FileCostStore) | 修正 |
| `snap-agent-memory-system.md` | ⚠️ 需验证代码准确性 | 验证后修正 |
| 其余 13 个文件 | ✅ 或 ⚠️ | 逐一验证 |

### 3.2 重新生成知识库（P1）

使用修正后的两个 skill 重新扫描 snap-agent 项目，生成准确的知识库文件：

```bash
# 在集成阶段执行（非运行时）
# 输入：project_root = snap-agent 项目根目录
# 输出：docs/knowledge/*.md
```

### 3.3 增量更新机制（P2）

代码变更后自动触发知识文件重新生成：
- 监听 `.java` 文件变更
- 识别受影响的子系统
- 重新生成对应知识文件

## 4. 验证标准

- [ ] 所有代码块中的类名在源码中存在
- [ ] 所有方法签名与源码一致
- [ ] 所有配置项在 @ConfigurationProperties 中定义
- [ ] 没有虚构的工具调用痕迹（read_code、generate_module_arch）
- [ ] 每个文件 100-200 行，不超过 300 行
