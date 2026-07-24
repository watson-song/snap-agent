# TDD需求规格说明书 — 代码图谱 (Code Graph)

> 版本: 1.0 | 模块: 12-codegraph | 基于 TEMPLATE.md

---

## 1. 需求元信息

```yaml
需求ID: REQ-12-CODEGRAPH
需求名称: 代码图谱构建与检索 (Code Graph)
优先级: P1
迭代: v0.8+
负责人: SnapAgent Team
状态: 开发中
```

### 1.1 背景与目标
- **业务背景**: SnapAgent 需要理解宿主项目代码结构，为 LLM 提供调用链、影响范围等代码级上下文。
- **用户价值**: AI 问答时能回答"谁调用了这个方法"、"改这个类影响哪些代码"等问题，无需人工翻代码。
- **成功指标**: 正则解析覆盖率 > 80% 常见 Java 模式；BFS 查询 < 10ms；循环检测 100%。

### 1.2 范围边界
- **包含**: `CodeGraph`、`CodeGraphNode`、`CodeGraphEdge`、`CodeGraphBuilder` (SPI)、`CodeGraphIndex` (SPI)、`SimpleCodeGraphBuilder`、`InMemoryCodeGraphIndex`、`CodeGraphToolProvider`。
- **不包含**: AST 级精确解析 (JavaParser/Spoon)、持久化索引 (SQLite/H2)、跨仓库分析。

### 1.3 风险与假设

| 风险ID | 描述 | 概率 | 影响 | 缓解 |
|--------|------|------|------|------|
| R1 | 正则解析对复杂 Java 语法误解析 | 中 | 中 | 标注为 regex 模式，后续可替换为 AST |
| R2 | 大项目 OOM | 低 | 高 | 文件大小限制 + 路径白名单 |
| R3 | BFS 遇到环死循环 | 中 | 高 | visited 集合去重 |

---

## 2. 用户故事 (User Stories)

### US-1: 构建代码图谱
```gherkin
作为 AI 技能开发者
我希望 SnapAgent 能解析宿主项目源码并构建代码图谱
以便 LLM 能回答代码结构问题
```
**AC:**
```gherkin
AC1: Given 宿主项目含 .java 文件
  When SimpleCodeGraphBuilder.build() 执行
  Then 返回 CodeGraph 含 class/method/field 节点
AC2: Given 项目为空
  When build() 执行
  Then 返回空 CodeGraph (nodeCount=0)
AC3: Given projectRoot 为 null
  When build() 执行
  Then 返回空 CodeGraph，不抛异常
```

### US-2: 节点与边的数据模型
```gherkin
作为 系统开发者
我希望 CodeGraphNode/CodeGraphEdge 不可变
以便 线程安全
```
**AC:**
```gherkin
AC4: Given CodeGraph 构造后
  When getNodes()/getEdges() 多次调用
  Then 返回不可变 List，修改抛 UnsupportedOperationException
```

### US-3: 解析继承与实现关系
```gherkin
作为 AI 技能开发者
我希望 图谱能识别 extends/implements
以便 回答"这个类的父类/接口是什么"
```
**AC:**
```gherkin
AC5: Given 源码含 "class Bar extends Foo implements Runnable"
  When build() 执行
  Then 边含 EXTENDS 和 IMPLEMENTS 类型
```

### US-4: 解析方法调用关系
```gherkin
作为 AI 技能开发者
我希望 图谱能识别方法调用
以便 回答"这个方法调用了哪些方法"
```
**AC:**
```gherkin
AC6: Given 源码含 "helper.execute()"
  When build() 执行
  Then 边含 CALLS 类型，toId 含 "execute"
```

### US-5: 包名过滤
```gherkin
作为 宿主开发者
我希望 只扫描指定包路径下的代码
以便 排除第三方依赖
```
**AC:**
```gherkin
AC7: Given scanPackages=["com.test.a"] 且项目含 com.test.a.ClassA 和 com.test.b.ClassB
  When build() 执行
  Then 节点含 ClassA 不含 ClassB
```

### US-6: 按名称检索节点
```gherkin
作为 LLM 工具
我希望 按名称模糊搜索代码节点
以便 快速定位类/方法
```
**AC:**
```gherkin
AC8: Given 索引含节点 "com.test.B#b()"
  When findByName("b")
  Then 返回匹配节点
AC9: Given 索引含节点 "com.test.InterfaceB"
  When findByName("INTERFACEB")
  Then 大小写不敏感匹配
AC10: Given 空或null pattern
  When findByName("") 或 findByName(null)
  Then 返回空列表
```

### US-7: 正向调用链
```gherkin
作为 LLM 工具
我希望 查询某方法的正向调用链
以便 了解它调用了哪些下游方法
```
**AC:**
```gherkin
AC11: Given A#a()→B#b()→C#c()
  When findCallChain("A#a()", 5)
  Then 返回 [B#b(), C#c()]
AC12: Given maxDepth=1
  When findCallChain("A#a()", 1)
  Then 只返回 [B#b()]
AC13: Given 不存在的方法
  When findCallChain("NonExistent#x()", 5)
  Then 返回空
AC14: Given A#a()→B#b()→A#a() 循环
  When findCallChain("A#a()", 10)
  Then 不死循环，返回 [B#b()]
```

### US-8: 反向调用链
```gherkin
作为 LLM 工具
我希望 查询谁调用了某方法
以便 评估变更影响面
```
**AC:**
```gherkin
AC15: Given A#a()→B#b() 和 D#d()→B#b()
  When findReverseCallChain("B#b()", 5)
  Then 返回 [A#a(), D#d()]
```

### US-9: 变更影响分析
```gherkin
作为 LLM 工具
我希望 分析某节点变更后受影响的节点
以便 做影响范围评估
```
**AC:**
```gherkin
AC16: Given B#b() 被 A#a() 和 D#d() 调用
  When findImpactScope("B#b()", 3)
  Then 返回含 A#a() 和 D#d()
```

### US-10: CodeGraphToolProvider 工具暴露
```gherkin
作为 LLM
我希望 通过工具调用访问代码图谱
以便 在对话中回答代码问题
```
**AC:**
```gherkin
AC17: Given 工具参数 tool="call_chain" query="A#a()"
  When provider.execute(args, ctx)
  Then 返回非错误结果，content 含 "正向调用链"
AC18: Given tool="unknown"
  When execute
  Then 返回错误 "unknown tool"
AC19: Given 缺少 tool 参数
  When execute
  Then 返回错误 "missing required parameter: tool"
AC20: Given tool="find" query="b"
  When execute
  Then content 含 "匹配节点" 和节点信息
AC21: Given provider.name()
  When 调用
  Then 返回 "code_graph_tools"
AC22: Given provider.schema()
  When 调用
  Then JSON 含 call_chain/reverse_chain/impact_analysis/find
```

---

## 2.5 用户故事地图

| 阶段 | 故事 | 价值 | 指标 | 依赖 |
|------|------|------|------|------|
| 构建 | US-1 | 代码结构化 | 节点>0 100% | - |
| 模型 | US-2 | 不可变安全 | 100% | US-1 |
| 解析 | US-3/4 | 关系识别 | 边类型正确 100% | US-1 |
| 过滤 | US-5 | 精准扫描 | 包名过滤 100% | US-1 |
| 检索 | US-6 | 模糊搜索 | 命中率 100% | US-1 |
| 调用链 | US-7/8 | 依赖分析 | 深度限制+循环 100% | US-1 |
| 影响 | US-9 | 变更评估 | 受影响节点 100% | US-1 |
| 工具 | US-10 | LLM 可用 | 4种工具全覆盖 | US-7/8/9 |

---

## 3. 功能规格 (Functional Specs)

### 3.1 用例清单

| 用例ID | 名称 | 优先级 | AC | 测试类型 |
|--------|------|--------|----|----------|
| UC-01 | 解析 class/method/field 声明 | P0 | AC1 | 单元 |
| UC-02 | 空项目返回空图 | P0 | AC2 | 单元 |
| UC-03 | null root 返回空图 | P0 | AC3 | 单元 |
| UC-04 | 不可变 nodes/edges | P0 | AC4 | 单元 |
| UC-05 | 解析 extends/implements | P0 | AC5 | 单元 |
| UC-06 | 解析方法调用 CALLS | P0 | AC6 | 单元 |
| UC-07 | 包名过滤 | P1 | AC7 | 单元 |
| UC-08 | 解析 interface/enum | P1 | - | 单元 |
| UC-09 | type() 返回 "regex" | P1 | - | 单元 |
| UC-10 | findByName 匹配 | P0 | AC8 | 单元 |
| UC-11 | findByName 大小写不敏感 | P0 | AC9 | 单元 |
| UC-12 | findByName 空/null | P0 | AC10 | 单元 |
| UC-13 | findCallChain 正向 | P0 | AC11 | 单元 |
| UC-14 | findCallChain maxDepth | P0 | AC12 | 单元 |
| UC-15 | findCallChain 不存在 | P0 | AC13 | 单元 |
| UC-16 | findCallChain 循环 | P0 | AC14 | 单元 |
| UC-17 | findReverseCallChain | P0 | AC15 | 单元 |
| UC-18 | findImpactScope | P0 | AC16 | 单元 |
| UC-19 | getNode by id | P0 | - | 单元 |
| UC-20 | getNode unknown null | P0 | - | 单元 |
| UC-21 | nodeCount | P0 | - | 单元 |
| UC-22 | getOutgoingEdges | P0 | - | 单元 |
| UC-23 | getIncomingEdges | P0 | - | 单元 |
| UC-24 | Tool: call_chain | P0 | AC17 | 单元 |
| UC-25 | Tool: reverse_chain | P0 | - | 单元 |
| UC-26 | Tool: impact_analysis | P0 | - | 单元 |
| UC-27 | Tool: find | P0 | AC20 | 单元 |
| UC-28 | Tool: unknown error | P0 | AC18 | 单元 |
| UC-29 | Tool: missing param error | P0 | AC19 | 单元 |
| UC-30 | Tool: maxDepth 参数 | P1 | - | 单元 |
| UC-31 | Tool: name() | P0 | AC21 | 单元 |
| UC-32 | Tool: schema() | P0 | AC22 | 单元 |
| UC-33 | Tool: 模糊匹配方法名 | P1 | - | 单元 |
| UC-34 | Tool: 无匹配返回提示 | P1 | - | 单元 |

### 3.2 详细用例 (Gherkin)

```gherkin
@priority:high @type:unit
功能: SimpleCodeGraphBuilder 解析

  场景: 解析 class/method/field 声明
    Given 源码含 public class Foo { private String name; public String getName() {...} public void setName(String name) {...} }
    When build()
    Then 节点含 CLASS "Foo"、METHOD "getName"/"setName"、FIELD "name"

  场景: 解析 extends/implements
    Given 源码 "class Bar extends Foo implements Runnable"
    When build()
    Then 边含 EXTENDS 和 IMPLEMENTS

  场景: 解析方法调用
    Given 源码含 "helper.execute()"
    When build()
    Then 边含 CALLS，toId 含 "execute"

  场景: 包名过滤
    Given scanPackages=["com.test.a"]，项目含 ClassA 和 com.test.b.ClassB
    When build()
    Then 节点含 ClassA，不含 ClassB

  场景: 空项目/null root
    Given 空目录 或 null root
    When build()
    Then 返回 nodeCount=0 edgeCount=0

  场景: 解析 interface/enum
    Given 源码含 "public interface MyInterface" 和 "public enum MyEnum"
    When build()
    Then 节点含 CLASS "MyInterface" 和 "MyEnum"
```

```gherkin
@priority:high @type:unit
功能: InMemoryCodeGraphIndex 检索

  场景: findByName 匹配
    Given 索引含 "com.test.B#b()" 和 class "B"
    When findByName("b")
    Then 返回含 name="b" 的节点

  场景: findByName 大小写不敏感
    When findByName("INTERFACEB")
    Then 返回 name="InterfaceB"

  场景: findCallChain 正向
    Given A#a()→B#b()→C#c()
    When findCallChain("A#a()", 5)
    Then 返回 [B#b(), C#c()]

  场景: findCallChain maxDepth
    When findCallChain("A#a()", 1)
    Then 只返回 [B#b()]

  场景: findCallChain 循环
    Given A#a()→B#b()→A#a()
    When findCallChain("A#a()", 10)
    Then 不死循环，返回 [B#b()]

  场景: findReverseCallChain
    Given A#a()→B#b() 和 D#d()→B#b()
    When findReverseCallChain("B#b()", 5)
    Then 返回 [A#a(), D#d()]

  场景: findImpactScope
    When findImpactScope("B#b()", 3)
    Then 返回含 A#a() 和 D#d()

  场景: getOutgoingEdges/getIncomingEdges
    When getOutgoingEdges("A#a()")
    Then 返回1条 CALLS 边
    When getIncomingEdges("B#b()")
    Then 返回2条 CALLS 边

  场景: getNode
    When getNode("com.test.C#c()")
    Then 返回非null，name="c"
    When getNode("nonexistent")
    Then 返回 null
```

```gherkin
@priority:high @type:unit
功能: CodeGraphToolProvider 工具

  场景: call_chain 工具
    Given args={tool:"call_chain", query:"com.test.A#a()"}
    When execute
    Then content 含 "正向调用链" 和 "B#b()" 和 "C#c()"

  场景: reverse_chain 工具
    Given args={tool:"reverse_chain", query:"com.test.B#b()"}
    When execute
    Then content 含 "反向调用链" 和 "A#a()"

  场景: impact_analysis 工具
    Given args={tool:"impact_analysis", query:"com.test.B#b()"}
    When execute
    Then content 含 "变更影响范围" 和 "A#a()"

  场景: find 工具
    Given args={tool:"find", query:"b"}
    When execute
    Then content 含 "匹配节点" 和 "B"

  场景: maxDepth 参数
    Given args={tool:"call_chain", query:"A#a()", max_depth:1}
    When execute
    Then content 含 "B#b()" 不含 "C#c()"

  场景: 未知工具
    Given args={tool:"unknown"}
    When execute
    Then error 含 "unknown tool"

  场景: 缺少参数
    Given args={query:"test"} (无tool)
    When execute
    Then error 含 "missing required parameter: tool"
    Given args={tool:"find"} (无query)
    When execute
    Then error 含 "missing required parameter: query"

  场景: 无匹配
    Given args={tool:"call_chain", query:"NonExistent"}
    When execute
    Then content 含 "未找到"

  场景: name/schema
    When name()
    Then 返回 "code_graph_tools"
    When schema()
    Then JSON 含 call_chain/reverse_chain/impact_analysis/find
```

---

## 4. 接口规格

```java
// SPI: 构建器
CodeGraph build();                    // 解析源码构建图谱
String type();                        // 构建器类型标识 ("regex")

// SPI: 索引
List<CodeGraphNode> findByName(String namePattern);
List<CodeGraphEdge> getOutgoingEdges(String nodeId);
List<CodeGraphEdge> getIncomingEdges(String nodeId);
List<CodeGraphNode> findCallChain(String methodId, int maxDepth);
List<CodeGraphNode> findReverseCallChain(String methodId, int maxDepth);
List<CodeGraphNode> findImpactScope(String nodeId, int maxDepth);
CodeGraphNode getNode(String id);
int nodeCount();
```

```yaml
# ToolProvider 暴露给 LLM 的工具
tool: code_graph_tools
params:
  tool: enum [call_chain, reverse_chain, impact_analysis, find]
  query: string (方法ID或名称模式)
  max_depth: int (可选，默认5)
返回: ToolResult content 含文本格式化结果
```

---

## 5. 数据规格

```yaml
CodeGraphNode:
  id: String (唯一标识，格式: "com.example.Foo#bar(String)")
  type: enum [CLASS, METHOD, FIELD]
  name: String (简短名称)
  packageName: String
  className: String
  returnType: String
  filePath: String
  lineNumber: int

CodeGraphEdge:
  fromId: String
  toId: String
  type: enum [CALLS, IMPLEMENTS, EXTENDS, DEPENDS_ON, OVERRIDES, REFERENCES]
  context: String (上下文信息如行号)

CodeGraph:
  nodes: List<CodeGraphNode> (不可变)
  edges: List<CodeGraphEdge> (不可变)
```

---

## 6. 错误处理

| 错误码 | 级别 | 描述 |
|--------|------|------|
| unknown tool | ERROR | 未知工具名 |
| missing required parameter: tool | ERROR | 缺少 tool 参数 |
| missing required parameter: query | ERROR | 缺少 query 参数 |
| 未找到 | INFO | 查询无匹配结果 |

---

## 7. 非功能需求

```yaml
性能: 构建P95<5s (千文件项目) | 查询P95<10ms | BFS循环检测100%
安全: CodePathGuard路径白名单 | 文件大小限制 | 扫描包名限制
```

---

## 8. 测试策略

### 8.2 已有测试覆盖

| 测试文件 | 模块 | 覆盖 | 数量 |
|----------|------|------|------|
| `SimpleCodeGraphBuilderTest` | starter | class/method/field解析、extends/implements、CALLS、包名过滤、空项目、null root、type()、interface/enum | 8 |
| `InMemoryCodeGraphIndexTest` | starter | findByName(匹配/大小写/空null)、findCallChain(正向/depth/不存在/循环)、findReverseCallChain、findImpactScope、getOutgoingEdges、getIncomingEdges、getNode(命中/null)、nodeCount | 14 |
| `CodeGraphToolProviderTest` | starter | call_chain、reverse_chain、impact_analysis、find、模糊匹配、unknown error、missing param、无匹配、name、schema、maxDepth | 13 |

**总结**: 3个测试文件，35个测试用例，覆盖全部 SPI 方法和工具提供者。

### 8.3 E2E 关键路径

| 路径ID | 关键路径 | 端点/组件 | 状态 |
|--------|----------|-----------|------|
| E2E-1 | 代码图谱构建: SimpleCodeGraphBuilder.build(projectRoot) → CodeGraph (class/method/field 节点 + CALLS/DEPENDS_ON 边) | SimpleCodeGraphBuilder | ✅已覆盖 (SimpleCodeGraphBuilderTest 8测试) |
| E2E-2 | 调用链查询: InMemoryCodeGraphIndex.findCallChain(src, dst, maxDepth) → 正向调用链 | InMemoryCodeGraphIndex | ✅已覆盖 (InMemoryCodeGraphIndexTest 14测试) |
| E2E-3 | 影响范围: InMemoryCodeGraphIndex.findImpactScope(className) → 反向调用链 | InMemoryCodeGraphIndex | ✅已覆盖 |
| E2E-4 | 工具执行: POST /runs (skillId=auto, tool=call_chain) → LLM tool_use → CodeGraphToolProvider.execute() → JSON 结果 | POST /runs, CodeGraphToolProvider | ⚠未实现 (GAP-6) |
| E2E-5 | SPI 可替换性: CodeGraphBuilder SPI 替换 → 新 builder 生效 → 构建结果不同 | CodeGraphBuilder SPI | ⚠未实现 (GAP-3 P2) |
| E2E-6 | 循环检测: findCallChain(A→B→A) → visited 集合去重 → 不死循环 | InMemoryCodeGraphIndex | ✅已覆盖 |

### 8.4 测试缺口

| ID | 描述 | 优先级 | 建议 |
|----|------|--------|------|
| GAP-1 | ✅已关闭: CodeGraph 不可变性已由 `CodeGraphTest` 覆盖 (getNodesShouldReturnUnmodifiableList/getEdgesShouldReturnUnmodifiableList/getNodesShouldThrowOnRemove/getNodesShouldThrowOnClear/getEdgesShouldThrowOnSet + defensive copy tests) | — | P2 |
| GAP-2 | `CodeGraphNode.toString` / `CodeGraphEdge.toString` 无断言 | P3 | 格式化输出验证 |
| GAP-3 | ⚠SPI集成: CodeGraphBuilder SPI 可替换性需 Spring 上下文或手动组装验证 (InMemoryCodeGraphIndex + mock builder) | P2 | 需 Spring 集成测试 |
| GAP-4 | ✅已关闭: DEPENDS_ON 边类型已由 `SimpleCodeGraphBuilderTest` 覆盖 (build_parsesDependsOnFromFieldType/build_parsesDependsOnMethodParam)。OVERRIDES/REFERENCES 不由 SimpleCodeGraphBuilder 产生，CodeGraphTest 验证所有 EdgeType 可存储。 | — | P2 |
| GAP-5 | ⚠功能缺失: SimpleCodeGraphBuilder.build() 未实现大文件跳过逻辑（CodePathGuard.validate 有 maxFileBytes 但 builder 直接用 Files.readAllBytes）。需先实现再测试。 | P2 | 功能未实现 |
| GAP-6 | ⚠E2E缺失: POST /runs (tool=call_chain/reverse_chain/impact_analysis) 端到端流程无 E2E 覆盖 — 见 E2E-4 | P2 | 需 E2E 集成测试 |

### 8.5 Mock策略
```yaml
无外部依赖需Mock。SimpleCodeGraphBuilder 使用真实文件系统 (@TempDir)。
CodeGraphToolProvider 使用真实 InMemoryCodeGraphIndex。
```

---

## 9. 依赖与前置条件

| 依赖 | 状态 | 降级 |
|------|------|------|
| CodePathGuard | 已完成 | 路径校验 |
| ToolProvider SPI | 已完成 | - |
| SimpleCodeGraphBuilder | 已完成 | 正则解析 |
| InMemoryCodeGraphIndex | 已完成 | 内存索引 |

---

## 10. 可观测性设计

```yaml
日志: INFO "CodeGraph built: nodes={}, edges={}, duration={}ms" | WARN "File skipped: too large {}"
```

---

## 11. 附录

### 12.1 变更历史
| 版本 | 日期 | 作者 | 内容 |
|------|------|------|------|
| 1.0 | 2026-07-24 | Team | 初始TDD规格 |

### 12.2 参考文档
- `snap-agent-core/src/main/java/.../codegraph/CodeGraph.java`
- `snap-agent-core/src/main/java/.../codegraph/CodeGraphNode.java`
- `snap-agent-core/src/main/java/.../codegraph/CodeGraphEdge.java`
- `snap-agent-core/src/main/java/.../codegraph/CodeGraphBuilder.java`
- `snap-agent-core/src/main/java/.../codegraph/CodeGraphIndex.java`
- `snap-agent-spring-boot-2x-starter/src/main/java/.../codegraph/SimpleCodeGraphBuilder.java`
- `snap-agent-spring-boot-2x-starter/src/main/java/.../codegraph/InMemoryCodeGraphIndex.java`
- `snap-agent-spring-boot-2x-starter/src/main/java/.../codegraph/CodeGraphToolProvider.java`

### 12.3 术语表
| 术语 | 定义 |
|------|------|
| CodeGraph | 不可变的代码图谱，含节点和边 |
| CodeGraphNode | 图谱节点 (CLASS/METHOD/FIELD) |
| CodeGraphEdge | 有向边 (CALLS/IMPLEMENTS/EXTENDS等) |
| CodeGraphBuilder | 构建器 SPI，默认实现为 regex |
| CodeGraphIndex | 索引 SPI，默认实现为内存 BFS |
| CodeGraphToolProvider | 将图谱能力暴露为 LLM 工具 |
