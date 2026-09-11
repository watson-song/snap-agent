# TDD需求规格说明书 — 代码图谱 (Code Graph)

> 版本: 1.2 (SnapAgent 2.x 架构适配 + 离线代码正文) | 模块: 12-codegraph | 基于 TEMPLATE.md
> 变更: CodeGraphToolProvider → @Tool 注解方法 (由 ToolCallbacks.from() 反射发现); CodeGraph 模型 (nodes/edges/BFS) 与正则解析保持不变; v1.2 新增 CodeGraphNode.sourceCode (关键代码片段) + code_view 工具,支持嵌入式运行时无源码场景离线查看业务代码

---

## 1. 需求元信息

```yaml
需求ID: REQ-12-CODEGRAPH
需求名称: 代码图谱构建与检索 (Code Graph)
优先级: P1
迭代: v0.8+ (2.x 适配)
负责人: SnapAgent Team
状态: 开发中
```

### 1.1 背景与目标
- **业务背景**: SnapAgent 需要理解宿主项目代码结构，为 LLM 提供调用链、影响范围等代码级上下文。SnapAgent 2.x 中，CodeGraph 模型保持不变，工具暴露方式从自定义 ToolProvider 改为 `@Tool` 注解方法，由 `ToolCallbacks.from()` 反射发现并注册到 `ToolCallbackRegistry`；生产装配已切换为 JavaParser AST 实现 `AstCodeGraphBuilder`（`SimpleCodeGraphBuilder` 保留为轻量备选并标记 `@Deprecated`）。**嵌入式部署时宿主 JVM 无源码**：v1.2 起 `AstCodeGraphBuilder` 在构建阶段抽取**完整类源码（含完整方法体）**写入 `CodeGraphNode.sourceCode`，随 H2 图谱持久化，运行时经 `code_view` 工具离线查看，实现"对话式代码诊断"（能定位具体 bug）。
- **用户价值**: AI 问答时能回答"谁调用了这个方法"、"改这个类影响哪些代码"等问题，无需人工翻代码；2.x 后 CodeGraph 工具与其他 @Tool 工具一致地参与图执行。**无源码的运行时也能查看关键业务代码正文并诊断问题**。
- **成功指标**: AST 解析覆盖常见 Java 模式；BFS 查询 < 10ms；循环检测 100%；@Tool 方法反射注册成功率 100%。

### 1.2 范围边界
- **包含**: `CodeGraph`、`CodeGraphNode`（含 `sourceCode`）、`CodeGraphEdge`、`CodeGraphBuilder` (SPI)、`CodeGraphIndex` (SPI)、`AstCodeGraphBuilder` (JavaParser AST，默认，含关键代码片段抽取)、`SimpleCodeGraphBuilder` (regex，@Deprecated 轻量备选)、`InMemoryCodeGraphIndex`、`H2CodeGraphIndex`（含 SOURCE_CODE 列与旧库迁移）、`CodeGraphTools` (含 `@Tool` 注解方法: call_chain/reverse_chain/impact_analysis/find/code_view/render_call_graph)。
- **不包含**: 完整 classpath 符号求解 (JavaSymbolSolver)、跨仓库分析、`CodeGraphToolProvider` 旧实现 (已删除)。

### 1.3 风险与假设

| 风险ID | 描述 | 概率 | 影响 | 缓解 |
|--------|------|------|------|------|
| R1 | regex 解析对复杂 Java 语法误解析 | 低 | 中 | 生产默认使用 AST (`AstCodeGraphBuilder`)；`SimpleCodeGraphBuilder` 仅作轻量备选并标 `@Deprecated` |
| R2 | 大项目 OOM | 低 | 高 | 文件大小限制 + 路径白名单 |
| R3 | BFS 遇到环死循环 | 中 | 高 | visited 集合去重 |
| R4 | @Tool 方法签名与 ToolCallbacks.from() 反射解析不兼容 | 低 | 中 | 扫描期校验 + 启动日志警告 |

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

### US-10: CodeGraphTools 通过 @Tool 暴露 (2.x 重构)
```gherkin
作为 LLM
我希望 通过 @Tool 注解方法访问代码图谱，由 ToolCallbacks.from() 反射注册到 ToolCallbackRegistry
以便 在对话中回答代码问题，与内置工具无差异
```
**AC:**
```gherkin
AC17: Given CodeGraphTools 类含 @Tool(name="call_chain") 方法
  When ToolCallbacks.from(codeGraphToolsInstance)
  Then 返回 ToolCallback[size=4] (call_chain/reverse_chain/impact_analysis/find)
  And 每个 ToolCallback.getJsonSchema() 含参数 schema

AC18: Given 工具参数 tool="call_chain" query="A#a()"
  When ToolCallback.execute(args, ctx)
  Then 返回非错误结果，content 含 "正向调用链"

AC19: Given tool="unknown"
  When execute
  Then 返回错误 "unknown tool"

AC20: Given 缺少 tool 参数
  When execute
  Then 返回错误 "missing required parameter: tool"

AC21: Given tool="find" query="b"
  When execute
  Then content 含 "匹配节点" 和节点信息

AC22: Given ToolCallback.getName() 对应 @Tool(name=...)
  When 调用
  Then 返回 "call_chain"/"reverse_chain"/"impact_analysis"/"find"

AC23: Given ToolCallbackRegistry 已注册 CodeGraph 的 4 个 ToolCallback
  When toToolDefinitionsJson()
  Then JSON 含 call_chain/reverse_chain/impact_analysis/find，与其他 @Tool 工具格式一致

AC24: Given CodeGraphTools 实例被销毁
  When ToolCallbackRegistry.unregister("call_chain") 等
  Then 4 个工具均不再可见，其他工具不受影响
```

### US-11: 离线关键代码正文 (sourceCode + code_view)
```gherkin
作为 嵌入式运行时的 LLM
我希望 在无源码的宿主 JVM 里仍能查看到关键业务代码片段
以便 对话式诊断"为什么出错、是代码哪里问题"
```
**AC:**
```gherkin
AC25: Given 源码含 public class OrderService (字段 + public 方法)
  When AstCodeGraphBuilder.build() 执行
  Then CLASS 节点 sourceCode 非空，含类名/字段名/public 方法签名与完整方法体

AC26: Given CLASS 节点 sourceCode 非空
  When H2CodeGraphIndex.loadGraph() 后 getNode(id)
  Then 节点 sourceCode 与原值一致 (SOURCE_CODE 列往返)

AC27: Given 旧版 H2 库 (CODE_GRAPH_NODES 无 SOURCE_CODE 列)
  When H2CodeGraphIndex 打开该库
  Then 自动 ALTER TABLE 加列，查询正常 (旧节点 sourceCode=null)

AC28: Given CodeGraphTools 含 @Tool(name="code_view")
  When ToolCallbacks.from() 反射
  Then ToolCallback 含 code_view (工具总数 = 6)

AC29: Given code_view query="OrderService" 且节点有 sourceCode
  When 执行
  Then content 含关键代码片段正文

AC30: Given code_view query 命中节点但 sourceCode 为空
  When 执行
  Then 返回提示信息 (不抛异常)
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
| 工具 | US-10 | LLM 可用 | 6种 @Tool 方法全覆盖 | US-7/8/9 |
| 离线代码 | US-11 | 无源码可诊断 | sourceCode 持久化 100% | US-1 |

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
| UC-09 | type() 返回 "javaparser" (默认) / "regex" (备选) | P1 | - | 单元 |
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
| UC-24 | @Tool: call_chain | P0 | AC18 | 单元 |
| UC-25 | @Tool: reverse_chain | P0 | - | 单元 |
| UC-26 | @Tool: impact_analysis | P0 | - | 单元 |
| UC-27 | @Tool: find | P0 | AC21 | 单元 |
| UC-28 | @Tool: unknown error | P0 | AC19 | 单元 |
| UC-29 | @Tool: missing param error | P0 | AC20 | 单元 |
| UC-30 | @Tool: maxDepth 参数 | P1 | - | 单元 |
| UC-31 | ToolCallbacks.from 反射发现 | P0 | AC17 | 单元 |
| UC-32 | toToolDefinitionsJson 一致 | P0 | AC23 | 单元 |
| UC-33 | unregister 不影响其他 | P1 | AC24 | 单元 |
| UC-34 | @Tool: 模糊匹配方法名 | P1 | - | 单元 |
| UC-35 | @Tool: 无匹配返回提示 | P1 | - | 单元 |
| UC-36 | AstCodeGraphBuilder 抽取类关键代码片段 (sourceCode) | P1 | AC25 | 单元 |
| UC-37 | sourceCode 含完整方法体 | P1 | AC25 | 单元 |
| UC-38 | H2 SOURCE_CODE 列持久化往返 | P1 | AC26 | 单元 |
| UC-39 | H2 旧库无 SOURCE_CODE 列自动迁移 | P1 | AC27 | 单元 |
| UC-40 | @Tool: code_view 返回代码片段 | P1 | AC28/AC29 | 单元 |
| UC-41 | @Tool: code_view 无片段返回提示 | P1 | AC30 | 单元 |

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
功能: CodeGraphTools @Tool 注解方法 (经 ToolCallbacks.from() 反射)

  场景: ToolCallbacks.from 反射发现 4 个 @Tool
    Given CodeGraphTools 类含 @Tool(name="call_chain")/@Tool(name="reverse_chain")/@Tool(name="impact_analysis")/@Tool(name="find")
    When ToolCallbacks.from(codeGraphToolsInstance)
    Then 返回 ToolCallback[size=4]
    And 每个 callback.getName() 与 @Tool(name=...) 一致
    And 每个 callback.getJsonSchema() 含参数 schema (query 必填, max_depth 可选)

  场景: call_chain 工具
    Given args={tool:"call_chain", query:"com.test.A#a()"}
    When callback.execute(args, ctx)
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

  场景: ToolCallbackRegistry 注册与 JSON 一致
    Given 4 个 ToolCallback 已注册到 ToolCallbackRegistry
    When toToolDefinitionsJson()
    Then JSON 含 call_chain/reverse_chain/impact_analysis/find
    And 与内置 @Tool 工具格式一致 (无 source 字段差异)

  场景: unregister 不影响其他工具
    Given CodeGraph 4 个 ToolCallback 已注册
    And 内置 @Tool 方法 "mysql_query" 也注册为 ToolCallback
    When ToolCallbackRegistry.unregister("call_chain") 等 4 个
    Then CodeGraph 工具不再可见
    And 内置 "mysql_query" 仍存在
```

---

## 4. 接口规格

```java
// SPI: 构建器 (不变)
CodeGraph build();                    // 解析源码构建图谱
String type();                        // 构建器类型标识 ("javaparser" 默认 / "regex" 备选)

// SPI: 索引 (不变)
List<CodeGraphNode> findByName(String namePattern);
List<CodeGraphEdge> getOutgoingEdges(String nodeId);
List<CodeGraphEdge> getIncomingEdges(String nodeId);
List<CodeGraphNode> findCallChain(String methodId, int maxDepth);
List<CodeGraphNode> findReverseCallChain(String methodId, int maxDepth);
List<CodeGraphNode> findImpactScope(String nodeId, int maxDepth);
CodeGraphNode getNode(String id);
int nodeCount();

// 2.x: CodeGraphTools 用 @Tool 注解方法暴露工具
public class CodeGraphTools {
    private final CodeGraphIndex index;

    @Tool(name = "call_chain", description = "查找方法的正向调用链")
    public ToolResult callChain(
        @ToolParam(description = "方法 ID，如 com.example.Foo#bar()") String query,
        @ToolParam(description = "最大深度", required = false) Integer maxDepth) { ... }

    @Tool(name = "reverse_chain", description = "查找反向调用链")
    public ToolResult reverseChain(...) { ... }

    @Tool(name = "impact_analysis", description = "分析变更影响范围")
    public ToolResult impactAnalysis(...) { ... }

    @Tool(name = "find", description = "按名称模糊匹配代码节点")
    public ToolResult find(...) { ... }
}

// 注册 (2.x): 由 ToolCallbacks.from() 反射发现 @Tool 方法
// ToolCallback[] callbacks = ToolCallbacks.from(codeGraphToolsInstance);
// Arrays.stream(callbacks).forEach(toolCallbackRegistry::register);
```

```yaml
# 2.x: CodeGraphTools 通过 @Tool 暴露给 LLM 的工具
# 由 ToolCallbacks.from() 反射发现，注册到 ToolCallbackRegistry
tools (经 @Tool 注解):
  - call_chain: { query: string (必填), max_depth: int (可选，默认5) }
  - reverse_chain: { query: string (必填), max_depth: int (可选，默认5) }
  - impact_analysis: { query: string (必填), max_depth: int (可选，默认3) }
  - find: { query: string (必填) }
  - code_view: { query: string (必填) }  # v1.2: 返回关键代码片段
  - render_call_graph: { query: string, graph_type: string, max_depth: int (可选) }
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
  sourceCode: String (关键代码片段，可null；v1.2新增，构建期抽取，随图谱持久化供离线查看)

CodeGraphEdge:
  fromId: String
  toId: String
  type: enum [CALLS, IMPLEMENTS, EXTENDS, DEPENDS_ON, OVERRIDES, REFERENCES]
  context: String (上下文信息如行号)

CodeGraph:
  nodes: List<CodeGraphNode> (不可变)
  edges: List<CodeGraphEdge> (不可变)

CodeGraphTools (2.x):
  index: CodeGraphIndex (注入)
  @Tool 方法: call_chain / reverse_chain / impact_analysis / find / code_view / render_call_graph
  反射: ToolCallbacks.from(instance) → ToolCallback[6]
```

---

## 6. 错误处理

| 错误码 | 级别 | 描述 |
|--------|------|------|
| unknown tool | ERROR | 未知工具名 (经 @Tool 反射后不应出现，仅手工调用时) |
| missing required parameter: tool | ERROR | 缺少 tool 参数 (经 @Tool 反射后由 ToolCallback 校验) |
| missing required parameter: query | ERROR | 缺少 query 参数 (@ToolParam required=true) |
| 未找到 | INFO | 查询无匹配结果 |
| TOOL_REFLECTION_ERR | ERROR | @Tool 方法签名无法被 ToolCallbacks.from() 解析 |

---

## 7. 非功能需求

```yaml
性能: 构建P95<5s (千文件项目) | 查询P95<10ms | BFS循环检测100% | ToolCallbacks.from()<100ms
安全: CodePathGuard路径白名单 | 文件大小限制 | 扫描包名限制
```

---

## 8. 测试策略

### 8.2 已有测试覆盖

| 测试文件 | 模块 | 覆盖 | 数量 |
|----------|------|------|------|
| `SimpleCodeGraphBuilderTest` | starter | class/method/field解析、extends/implements、CALLS、包名过滤、空项目、null root、type()、interface/enum、大文件跳过 | 9 |
| `InMemoryCodeGraphIndexTest` | starter | findByName(匹配/大小写/空null)、findCallChain(正向/depth/不存在/循环)、findReverseCallChain、findImpactScope、getOutgoingEdges、getIncomingEdges、getNode(命中/null)、nodeCount | 14 |
| `AstCodeGraphBuilderTest` | starter | AST解析、大文件跳过、完整类源码抽取(sourceCode 含完整方法体) | 32 |
| `H2CodeGraphIndexTest` | starter | H2持久化、SOURCE_CODE 列往返、旧库无列自动迁移 | 17 |
| `CodeGraphToolsTest` | starter | ToolCallbacks.from 反射发现 6 个 @Tool、call_chain、reverse_chain、impact_analysis、find、code_view、模糊匹配、unknown error、missing param、无匹配、maxDepth、toToolDefinitionsJson 一致、unregister 不影响其他 | 21 |

**总结**: 5个测试文件，覆盖全部 SPI 方法、@Tool 反射注册和 6 种工具调用、关键代码片段抽取与 H2 持久化迁移。CodeGraph 模型与正则解析部分与 1.0 版本完全一致；工具暴露方式改为 @Tool 注解方法，v1.2 新增 sourceCode + code_view 离线代码正文能力。

### 8.3 E2E 关键路径

| 路径ID | 关键路径 | 端点/组件 | 状态 |
|--------|----------|-----------|------|
| E2E-1 | 代码图谱构建: SimpleCodeGraphBuilder.build(projectRoot) → CodeGraph (class/method/field 节点 + CALLS/DEPENDS_ON 边) | SimpleCodeGraphBuilder | ✅已覆盖 (SimpleCodeGraphBuilderTest 8测试) |
| E2E-2 | 调用链查询: InMemoryCodeGraphIndex.findCallChain(src, dst, maxDepth) → 正向调用链 | InMemoryCodeGraphIndex | ✅已覆盖 (InMemoryCodeGraphIndexTest 14测试) |
| E2E-3 | 影响范围: InMemoryCodeGraphIndex.findImpactScope(className) → 反向调用链 | InMemoryCodeGraphIndex | ✅已覆盖 |
| E2E-4 | 工具执行: POST /runs (skillId=auto, tool=call_chain) → LLM tool_use → ToolsNode 调 CodeGraph ToolCallback → JSON 结果 | POST /runs, ToolsNode + CodeGraphTools | ⚠未实现 (GAP-6) |
| E2E-5 | SPI 可替换性: CodeGraphBuilder SPI 替换 → 新 builder 生效 → 构建结果不同 | CodeGraphBuilder SPI | ⚠未实现 (GAP-3 P2) |
| E2E-6 | 循环检测: findCallChain(A→B→A) → visited 集合去重 → 不死循环 | InMemoryCodeGraphIndex | ✅已覆盖 |
| E2E-7 | @Tool 反射注册: ToolCallbacks.from(codeGraphToolsInstance) → ToolCallback[4] → ToolCallbackRegistry 注册 → toToolDefinitionsJson 含 4 工具 | ToolCallbacks + ToolCallbackRegistry | ✅已覆盖 (CodeGraphToolsTest) |

### 8.4 测试缺口

| ID | 描述 | 优先级 | 建议 |
|----|------|--------|------|
| GAP-1 | ✅已关闭: CodeGraph 不可变性已由 `CodeGraphTest` 覆盖 (getNodesShouldReturnUnmodifiableList/getEdgesShouldReturnUnmodifiableList/getNodesShouldThrowOnRemove/getNodesShouldThrowOnClear/getEdgesShouldThrowOnSet + defensive copy tests) | — | P2 |
| GAP-2 | `CodeGraphNode.toString` / `CodeGraphEdge.toString` 无断言 | P3 | 格式化输出验证 |
| GAP-3 | ⚠SPI集成: CodeGraphBuilder SPI 可替换性需 Spring 上下文或手动组装验证 (InMemoryCodeGraphIndex + mock builder) | P2 | 需 Spring 集成测试 |
| GAP-4 | ✅已关闭: DEPENDS_ON 边类型已由 `SimpleCodeGraphBuilderTest` 覆盖 (build_parsesDependsOnFromFieldType/build_parsesDependsOnMethodParam)。OVERRIDES/REFERENCES 不由 SimpleCodeGraphBuilder 产生，CodeGraphTest 验证所有 EdgeType 可存储。 | — | P2 |
| GAP-5 | ✅已关闭: 大文件跳过逻辑已由 `AstCodeGraphBuilderTest.shouldSkipFileLargerThanMaxFileBytes` 和 `SimpleCodeGraphBuilderTest.shouldSkipFileLargerThanMaxFileBytes` 覆盖 — build() 在文件收集阶段用 `CodePathGuard.getMaxFileBytes()` 跳过超限文件，避免 OOM。 | — | P2 |
| GAP-6 | ⚠E2E缺失: POST /runs (tool=call_chain/reverse_chain/impact_analysis) 端到端流程无 E2E 覆盖 — 见 E2E-4 | P2 | 需 E2E 集成测试 |
| GAP-7 | ✅已关闭: @Tool 反射注册 (ToolCallbacks.from + ToolCallbackRegistry) 已由 `CodeGraphToolsTest` 覆盖 (shouldReflectFourToolMethods/shouldGenerateConsistentJsonSchema/shouldUnregisterWithoutAffectingOthers) | — | P0 |
| GAP-8 | ✅已关闭: 关键代码片段抽取与持久化已由 `AstCodeGraphBuilderTest` (shouldExtractKeySourceExcerptForClass/shouldNotStoreMethodBodyInClassExcerpt)、`H2CodeGraphIndexTest` (shouldPersistSourceCodeField/shouldMigrateLegacySchemaWithoutSourceCodeColumn)、`CodeGraphToolsTest` (codeView_*) 覆盖 | — | P1 |

### 8.5 Mock策略
```yaml
无外部依赖需Mock。SimpleCodeGraphBuilder 使用真实文件系统 (@TempDir)。
CodeGraphTools 使用真实 InMemoryCodeGraphIndex。
ToolCallbacks.from() 使用真实反射 (无 mock)。
ToolCallbackRegistry 使用 Mockito mock (验证 register/unregister 调用)。
```

---

## 9. 依赖与前置条件

| 依赖 | 状态 | 降级 |
|------|------|------|
| CodePathGuard | 已完成 | 路径校验 |
| ToolCallbackRegistry SPI (2.x) | 已完成 | - |
| ToolCallbacks.from() 反射工具 (2.x) | 已完成 | - |
| AstCodeGraphBuilder | 已完成 | JavaParser AST（默认） |
| SimpleCodeGraphBuilder | 已完成 | 正则解析（@Deprecated 轻量备选） |
| InMemoryCodeGraphIndex | 已完成 | 内存索引 |
| H2CodeGraphIndex | 已完成 | H2 持久化索引 |

---

## 10. 可观测性设计

```yaml
日志: INFO "CodeGraph built: nodes={}, edges={}, duration={}ms" | WARN "File skipped: too large {}"
      INFO "CodeGraphTools registered: 4 ToolCallbacks via ToolCallbacks.from()"
指标: codegraph_build_duration_seconds | codegraph_query_duration_seconds{tool} | codegraph_tool_call_total{tool}
```

---

## 11. 附录

### 12.1 变更历史
| 版本 | 日期 | 作者 | 内容 |
|------|------|------|------|
| 1.0 | 2026-07-24 | Team | 初始TDD规格 |
| 1.1 | 2026-07-25 | Team | 适配 2.x: CodeGraphToolProvider 改为 CodeGraphTools (@Tool 注解方法); 由 ToolCallbacks.from() 反射发现并注册到 ToolCallbackRegistry; CodeGraph 模型与正则解析保持不变; 新增 UC-31/32/33 反射注册/JSON 一致/unregister 隔离 |
| 1.2 | 2026-09-11 | Team | 新增 CodeGraphNode.sourceCode 完整类源码 + code_view 工具 (US-11, AC25~AC30, UC-36~41): AstCodeGraphBuilder 构建期抽取完整类源码(含完整方法体,上限64KB,仅防病态单类;"关键点优先"由 scanMode=skills 过滤保证), H2 SOURCE_CODE 列 + 旧库自动迁移, 支持嵌入式无源码运行时离线诊断定位 bug |
| 1.3 | 2026-09-11 | Team | 新增 SkillKeywordExtractor(纯 Java 工具,抽取 skill 类名/方法名/表名/列名/包名关键字,供 CLI 与运行时共用保证一致); CodeGraphCli 重构出可测 run() 方法并新增 --scan-mode/--skill-dir 参数,支持集成阶段按 skill 关键类预构建 H2; 新增 SkillKeywordExtractorTest(3)/CodeGraphCliTest(1 E2E) |
| 1.4 | 2026-09-11 | Team | 两个 builder(Ast/Simple)文件收集阶段排除 target/build/.git/.idea/node_modules 与 src/test 目录,避免 package 阶段自动构建时把编译产物/生成代码/测试代码污染进图谱; 新增 AstCodeGraphBuilderTest.shouldSkipTargetAndTestDirectories / SimpleCodeGraphBuilderTest.shouldSkipTargetAndTestDirectories |

### 12.2 参考文档
- `snap-agent-core/src/main/java/.../codegraph/CodeGraph.java`
- `snap-agent-core/src/main/java/.../codegraph/CodeGraphNode.java`
- `snap-agent-core/src/main/java/.../codegraph/CodeGraphEdge.java`
- `snap-agent-core/src/main/java/.../codegraph/CodeGraphBuilder.java`
- `snap-agent-core/src/main/java/.../codegraph/CodeGraphIndex.java`
- `snap-agent-spring-boot-2x-starter/src/main/java/.../codegraph/SimpleCodeGraphBuilder.java`
- `snap-agent-spring-boot-2x-starter/src/main/java/.../codegraph/InMemoryCodeGraphIndex.java`
- `snap-agent-spring-boot-2x-starter/src/main/java/.../codegraph/CodeGraphTools.java` (2.x 重命名自 CodeGraphToolProvider)
- `docs/superpowers/specs/2026-07-25-architecture-refactor-2x-design.md`

### 12.3 术语表
| 术语 | 定义 |
|------|------|
| CodeGraph | 不可变的代码图谱，含节点和边 |
| CodeGraphNode | 图谱节点 (CLASS/METHOD/FIELD) |
| CodeGraphEdge | 有向边 (CALLS/IMPLEMENTS/EXTENDS等) |
| CodeGraphBuilder | 构建器 SPI，默认实现为 AstCodeGraphBuilder (javaparser) |
| CodeGraphIndex | 索引 SPI，默认实现为内存 BFS |
| CodeGraphTools | 2.x 工具类，含 @Tool 注解方法，由 ToolCallbacks.from() 反射注册为 ToolCallback[] |
| ToolCallbacks.from() | 2.x 反射工具，自动发现 @Tool 方法并包装为 ToolCallback |
| ToolCallbackRegistry | 2.x 工具注册表 SPI，CodeGraph 工具与内置 @Tool 工具统一注册 |
