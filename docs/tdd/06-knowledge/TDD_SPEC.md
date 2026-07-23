# TDD需求规格说明书 — 业务知识库 (Knowledge Base)

> 版本: 2.0 | 模块: 06-knowledge | 基于 TEMPLATE.md

---

## 1. 需求元信息

```yaml
需求ID: REQ-06-KNOWLEDGE
需求名称: 嵌入式业务知识库
优先级: P0
迭代: v0.7+
负责人: SnapAgent Team
状态: 开发中
```

### 1.1 背景与目标
- **业务背景**: Agent 此前只能"查数据"，回答脱离业务上下文。需让 Agent 具备业务领域知识。
- **用户价值**: 提问时自动检索相关知识注入 prompt，回答精准度提升 50%+。
- **成功指标**: 检索 P95 < 10ms；knowledge.enabled=false 时零新增 bean。

### 1.2 范围边界
- **包含**: `KnowledgeBase`、`KnowledgeFragment`、`KnowledgeInjector`、`SimpleKeywordSearcher`、`MarkdownKnowledgeSource`、`KnowledgeSedimentationExtractor`。
- **不包含**: 向量嵌入检索(v0.7.2)、外部API知识源(v0.7.1)、热重载WatchService(v0.7.1)。

### 1.3 风险与假设

| 风险ID | 描述 | 概率 | 影响 | 缓解 |
|--------|------|------|------|------|
| R1 | 中文bigram分词精度有限 | 中 | 中 | title 2x加权补偿 |
| R2 | Markdown格式不规范致分段异常 | 低 | 中 | 无##时整文件作为单fragment |
| R3 | 知识源加载失败 | 低 | 中 | try-catch单源隔离 |

---

## 2. 用户故事 (User Stories)

### US-1: 用户提问时自动注入业务知识
```gherkin
作为 Agent 用户
我希望 提问时自动检索相关知识注入prompt
以便 LLM回答基于业务上下文
```
**AC:**
```gherkin
AC1: Given 知识库含"数据库诊断"片段内容含"连接池"
  When 提问含"连接池"
  Then extend返回非空，含"业务知识参考"和片段内容
AC2: Given 提问与所有片段无重叠
  When extend执行
  Then 返回空字符串
```

### US-2: 从Markdown文件自动加载知识
```gherkin
作为 系统管理员
我希望 放置.md文件即可自动加载为知识片段
以便 无需开发代码即可维护知识
```
**AC:**
```gherkin
AC3: Given 文件含#标题和多个##章节
  When load()
  Then 每个##章节为一个fragment，##前简介为overview
  And 所有metadata.category来自#标题
AC4: Given 文件无##标题
  When load()
  Then 生成1个fragment，content为全文
```

### US-3: 关键词检索匹配相关知识
```gherkin
作为 系统开发者
我希望 基于词频重叠评分，标题命中加权
以便 无需向量模型即可基本语义匹配
```
**AC:**
```gherkin
AC5: Given 标题含关键词但内容不含 vs 内容含但标题不含
  When score对两者评分
  Then 标题匹配分数更高(2x加权)
AC6: Given 查询"补货策略"
  When tokenize执行
  Then 生成["补货","货策","策略"]三个bigram
```

### US-4: 经验沉淀自动转化为知识
```gherkin
作为 运维工程师
我希望 问题关闭后将"问题→根因→方案"沉淀为知识
以便 下次同类问题Agent可参考历史经验
```
**AC:**
```gherkin
AC7: Given IssueClosure含userQuery/rootCause/solution
  When extract(issue)
  Then title="问题:{query}"(超60字截断)
  And source="sedimentation:{issueId}" 且 metadata含category="经验沉淀"
  And content含##问题/##根因/##解决方案
```

### US-5: 知识库热重载
```gherkin
作为 系统管理员
我希望 更新知识文件后可热重载
以便 无需重启即可更新
```
**AC:**
```gherkin
AC8: Given 源首次返回1片段，reload后2片段
  When KnowledgeBase.reload()
  Then size()从1变2，search基于新内容
AC9: Given 源A正常、源B load()抛异常
  When 构造KnowledgeBase
  Then A片段正常加载，B异常被catch
```

### US-6: 检索数量限制 topK
```gherkin
作为 系统开发者
我希望 search 返回结果按 score 降序排列且不超过 topK
  以便 注入 prompt 的知识片段数量可控
```
**AC:**
```gherkin
AC10: Given 知识库含 5 个匹配片段且 topK=3
  When search("query", 3, 0.0)
  Then 返回最多 3 个片段
  And 片段按 score 降序排列
```

### US-7: 最低评分阈值 minScore 过滤
```gherkin
作为 系统开发者
我希望 score 低于 minScore 的片段被过滤
  以便 只注入高相关度的知识
```
**AC:**
```gherkin
AC11: Given 片段 A score=0.8, 片段 B score=0.3
  When search("query", 5, 0.5)
  Then 仅返回片段 A
  And 片段 B 被过滤
```

### US-8: 空查询防御处理
```gherkin
作为 系统开发者
我希望 search 对 null 或空查询安全返回空列表
  以便 不抛 NPE 影响主流程
```
**AC:**
```gherkin
AC12: Given 查询为 null
  When search(null, 5, 0.5)
  Then 返回空列表且不抛异常
AC13: Given 查询为空串 ""
  When search("", 5, 0.5)
  Then 返回空列表
```

---

## 2.5 用户故事地图

| 阶段 | 故事 | 价值 | 指标 | 依赖 |
|------|------|------|------|------|
| 加载 | US-2 | 零代码维护 | 分段正确率100% | - |
| 检索 | US-3 | 基本语义匹配 | P95<10ms | US-2 |
| 注入 | US-1 | 回答精准 | 相关度+50% | US-3 |
| 沉淀 | US-4 | 经验复用 | 覆盖率>80% | US-2 |
| 更新 | US-5 | 无需重启 | <500ms | US-2 |
| 限量 | US-6 | 数量可控 | topK 限制 100% | US-3 |
| 质量 | US-7 | 高相关度 | minScore 过滤 100% | US-3 |
| 防御 | US-8 | 不崩坏 | null 安全 100% | US-3 |

---

## 3. 功能规格 (Functional Specs)

### 3.1 用例清单

| 用例ID | 名称 | 优先级 | AC | 类型 |
|--------|------|--------|----|------|
| UC-01 | 检索返回topK片段 | P0 | AC1 | 单元 |
| UC-02 | 无匹配返回空 | P0 | AC2 | 单元 |
| UC-03 | topK限制数量 | P0 | - | 单元 |
| UC-04 | minScore过滤 | P0 | - | 单元 |
| UC-05 | 空/null查询返回空 | P0 | - | 单元 |
| UC-06 | Markdown按##分段 | P0 | AC3 | 单元 |
| UC-07 | 无##整文加载 | P0 | AC4 | 单元 |
| UC-08 | 标题2x加权 | P0 | AC5 | 单元 |
| UC-09 | 中文bigram分词 | P0 | AC6 | 单元 |
| UC-10 | 经验沉淀提取 | P0 | AC7 | 单元 |
| UC-11 | reload热重载 | P1 | AC8 | 单元 |
| UC-12 | 单源失败隔离 | P1 | AC9 | 单元 |

### 3.2 详细用例 (Gherkin)

```gherkin
@priority:high @type:unit
功能: KnowledgeBase 检索 + SimpleKeywordSearcher 评分

  场景: 查询匹配片段返回排序topK
    Given 知识库含f1(标题="数据库诊断",内容含"连接池")和f2(标题="日志分析",内容含"OOM")
    And searcher对f1评分1.0，f2评分0.0
    When search("连接池", 5, 0.5)
    Then 返回1个片段，标题为"数据库诊断"

  场景: 无知识源时size=0且无匹配返回空
    Given sources为空 或 f1内容="连接池"
    When search("x", 5, 0.0) 或 search("Redis缓存", 5, 0.5)
    Then 均返回空列表

  场景: 标题2x加权高于内容匹配
    Given titleOnly(标题含关键词,内容不含)和contentOnly(反之)
    When score 对两者评分
    Then titleOnly≈1.0 > contentOnly≈0.5

  场景: 中文bigram分词
    When tokenize("补货策略")
    Then 返回["补货","货策","策略"]

  场景: 英文分词小写化过滤短词
    When tokenize("Hello, World! a I db")
    Then 含"hello","world","db"，不含"a","i"

  场景: 分数截断[0,1]且大小写不敏感
    Given fragment(title="Database Diagnostics", content="database connection pool")
    When score("DATABASE CONNECTION", fragment)
    Then <= 1.0 且 > 0.5

  场景: reload重新加载所有源
    Given 动态源首次1片段，reload后2片段
    When KnowledgeBase.reload()
    Then size()从1变2

  场景: 单源加载失败不影响其他源
    Given 源A正常、源B load()抛异常
    When 构造KnowledgeBase
    Then A片段正常加载，B被catch+WARN日志
```

```gherkin
@priority:high @type:unit
功能: MarkdownKnowledgeSource + KnowledgeInjector + SedimentationExtractor + Fragment

  场景: 按##分段生成overview+sections
    Given 文件"# Test\n简介\n## S1\n内容1\n## S2\n内容2"
    When load()
    Then 3片段：overview含"概述"，S1，S2
    And source为"file.md:overview"/"section-1"/"section-2"
    And 所有metadata.category="Test"

  场景: 无##生成单fragment
    Given "# Title\n纯内容"
    When load()
    Then 1片段，title="Title"，source="file.md:overview"

  场景: classpath加载且跳过非.md文件
    Given dir="classpath:/docs/knowledge/" 或目录含readme.txt+real.md
    When load()
    Then classpath返回非空且category="SnapAgent 业务知识示例"
    And 文件系统仅加载real.md片段，不存在目录返回空

  场景: 匹配查询注入知识section
    Given 知识库含"Database Diagnostics"内容含"database connection pool"
    When extend(skill, task("database connection pool"))
    Then 非空，含"业务知识参考"和"来源:"
    And null task/空inputs时返回空串

  场景: maxFragments限制且中文查询匹配
    Given 5匹配片段maxFragments=2 或 知识库含"补货策略"
    When extend(skill, task("database content")) 或 extend(skill, task("补货策略怎么配置"))
    Then 前者含"知识片段1"和"2"不含"3"，后者非空含"补货策略"

  场景: 沉淀提取含title/source/metadata/章节
    Given IssueClosure(issueId="issue-001", userQuery="为什么订单服务超时?", rootCause="连接池打满")
    When extract(issue)
    Then title="问题: 为什么订单服务超时?" 且 source="sedimentation:issue-001"
    And metadata含category="经验沉淀"
    And content含"##问题"+"##根因"+"##解决方案"

  场景: selectedSolution优先不列选项
    Given suggestion=[方案1,方案2], selectedSolution="方案2: 加索引"
    When extract(issue)
    Then content含"方案2: 加索引"，不含"- [medium] 方案1"

  场景: 超长query截断且Fragment不可变
    Given userQuery=80字符 且 metadata={key:"value"}
    When extract(issue) 且 构造KnowledgeFragment后修改原始map
    Then title以"..."结尾长度<=67，content含完整query
    And fragment.metadata不受影响且getMetadata().put()抛UnsupportedOperationException
```

---

## 4. 接口规格

```java
// KnowledgeBase.search — 返回topK片段(score>=minScore)
List<KnowledgeFragment> search(String query, int topK, double minScore);
// SimpleKeywordSearcher.score — (titleHits×2+contentHits)/(queryTokens×2), [0,1]
double score(String query, KnowledgeFragment fragment);
// KnowledgeInjector.extend — 检索→格式化注入system prompt
String extend(SkillMeta skill, AgentTask task);
// KnowledgeSedimentationExtractor.extract — IssueClosure→KnowledgeFragment
KnowledgeFragment extract(IssueClosure issue);
```

---

## 5. 数据规格

```yaml
KnowledgeFragment(不可变): title, content(Markdown), source, metadata(Map, null→empty, 防御拷贝, unmodifiable)
SearchResult: {fragment, score:double[0,1]}
缓存: volatile List (启动全量加载)
```

---

## 6. 错误处理

| 错误码 | 级别 | 描述 |
|--------|------|------|
| SOURCE_LOAD_FAILED | WARN | 知识源load()失败 |
| SOURCE_RELOAD_FAILED | WARN | 知识源reload()失败 |

```gherkin
场景: 源加载异常隔离
  Given 源B load()抛RuntimeException
  When 构造KnowledgeBase
  Then B被catch+WARN日志，A片段正常
```

---

## 7. 非功能需求

```yaml
性能: 检索P95<10ms(100片段) | 加载<500ms(10文件) | 格式化<1ms | 内存<5MB
可测试性: 核心覆盖>80% | Searcher参数化 | @TempDir隔离
```

---

## 8. 测试策略

### 8.2 已有测试覆盖

| 测试文件 | 数量 | 覆盖 |
|----------|------|------|
| `KnowledgeBaseTest` | 8 | 检索匹配/无匹配/topK/minScore/空查询/无源/reload/size |
| `KnowledgeFragmentTest` | 5 | getter/toString/防御拷贝/unmodifiable/null metadata |
| `KnowledgeInjectorTest` | 9 | 匹配注入/无匹配/空inputs/null task/maxFragments/minScore/多input/来源/中文 |
| `SimpleKeywordSearcherTest` | 15 | 英文/无重叠/空null/标题2x/中文bigram/部分匹配/满分/截断/单token/大小写/分词器 |
| `MarkdownKnowledgeSourceTest` | 10 | ##分段/无##/无H1/多文件/classpath/不存在/空目录/跳过非md/type/reload |
| `KnowledgeSedimentationExtractorTest` | 7 | title/source/章节/selectedSolution/列方案/验证结果/截断/metadata |

**总结**: 六个核心类全部有单元测试，public方法覆盖率 > 80%。

### 8.3 测试缺口

| ID | 描述 | 优先级 | 建议 |
|----|------|--------|------|
| GAP-1 | `searchWithScores`多片段降序验证不足 | P1 | 3+片段按score降序 |
| GAP-2 | `listAll`不可变性验证缺失 | P1 | 修改返回列表抛异常 |
| GAP-3 | 混合中英文分词场景 | P2 | "database 连接池"分词 |
| GAP-4 | `MarkdownKnowledgeSource`嵌套目录递归 | P2 | 子目录.md文件加载 |
| GAP-5 | `KnowledgeInjector`+AgentExecutor集成 | P1 | 多SystemPromptExtender拼接 |
| GAP-6 | 无suggestion且无selectedSolution边界 | P2 | extract content格式 |
| GAP-7 | `SearchResult`值对象无测试 | P3 | getter验证 |

### 8.4 Mock策略
```yaml
Mock: KnowledgeSource(匿名实现), KnowledgeSearcher(lambda), AgentTask/SkillMeta(真实对象)
文件: @TempDir创建临时.md文件
```

---

## 9. 依赖与前置条件

| 依赖 | 状态 | 降级 |
|------|------|------|
| Spring ResourcePatternResolver | 已完成 | classpath模式需要 |
| 无外部NLP依赖 | - | bigram自实现 |

---

## 10. 可观测性设计

```yaml
日志: INFO "loaded {} fragments from {} file(s) in {}" | DEBUG "Search query='{}',results={}" | WARN "source {} load failed"
```

---

## 11. 原型与交互参考

| 状态 | 表现 | 说明 |
|------|------|------|
| 已加载 | 知识库modal列片段 | listAll()驱动 |
| 检索中 | 无UI变化 | <10ms |
| 注入成功 | prompt含知识section | LLM回答含上下文 |
| 无匹配 | prompt无变化 | 不影响回答 |

---

## 12. 附录

### 12.1 变更历史
| 版本 | 日期 | 作者 | 内容 |
|------|------|------|------|
| 2.0 | 2026-07-23 | Team | 初始TDD规格 |

### 12.2 参考文档
- `docs/superpowers/specs/2026-07-16-v0.7-knowledge-base-design.md`
- `snap-agent-core/.../knowledge/` (KnowledgeBase, KnowledgeFragment, KnowledgeSearcher, KnowledgeSource, SearchResult)
- `snap-agent-spring-boot-2x-starter/.../knowledge/` (KnowledgeInjector, MarkdownKnowledgeSource, SimpleKeywordSearcher)
- `snap-agent-spring-boot-2x-starter/.../issue/KnowledgeSedimentationExtractor.java`

### 12.3 术语表
| 术语 | 定义 |
|------|------|
| KnowledgeFragment | 不可变知识片段(title+content+source+metadata) |
| KnowledgeBase | 知识库，管理多源+检索，启动全量加载 |
| KnowledgeSource | 知识源SPI，load()返回片段，reload()热重载 |
| KnowledgeSearcher | 检索算法SPI，score()返回[0,1] |
| KnowledgeInjector | SystemPromptExtender实现，运行时检索注入 |
| SimpleKeywordSearcher | 词频重叠评分，英文空格+小写，中文bigram，title 2x |
| MarkdownKnowledgeSource | 从.md按##分段加载 |
| KnowledgeSedimentationExtractor | IssueClosure→知识片段 |
| bigram | 中文2字符滑动窗口分词 |
