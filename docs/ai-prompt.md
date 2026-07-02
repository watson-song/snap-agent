# AI 提示词 — 使用 SnapAgent 诊断业务问题

> 将以下提示词粘贴到 Claude Code / ChatGPT / 其他 LLM 中，让 AI 通过 SnapAgent 诊断业务问题。

---

## 提示词模板

```
你是一个业务系统诊断助手。请通过 SnapAgent Web UI 帮我诊断以下问题：

## 问题描述
{在这里描述你遇到的问题，例如：某个SKU未生成补货策略}

## 系统信息
- 环境: {uat / sit / prod}
- 项目: {项目名称}
- 关键参数:
  - skuCode: {商品编码}
  - warehouseCode: {仓库编码，可选}

## 操作步骤
1. 打开 http://localhost:8080/snap-agent/
2. 在左侧选择对应的诊断 Skill
3. 在输入框中描述问题
4. 等待 Agent 思考和查询数据库
5. 查看诊断结果和修复建议

## 注意事项
- Agent 只执行只读 SELECT 查询，不会修改数据
- 如果环境是 prod，Agent 会提供 SQL 让你手动执行
- 如果 Agent 询问参数，请提供完整的 skuCode、仓库等信息
```

---

## 精简版（一行提示词）

```
通过 SnapAgent (http://localhost:8080/snap-agent/) 诊断：{问题描述}，环境={env}，skuCode={sku}
```

---

## 编写自定义 Skill 的 AI 提示词

```
请帮我编写一个 SnapAgent 的 Skill 文件 (.md)，用于诊断以下业务问题：

## 诊断场景
{描述需要诊断的业务问题}

## 涉及的数据库表
- 表名: {table_name}
- 关键字段: {field1, field2, ...}
- 表关系: {表之间的关联关系}

## 诊断流程
1. 收集信息: {需要用户提供哪些参数}
2. 查询数据: {需要执行哪些 SQL}
3. 分析判断: {各查询结果的判断逻辑}
4. 输出报告: {报告格式}

请按照 SnapAgent 的 Skill Markdown 格式输出，包含 YAML frontmatter (name, description) 和 Markdown body。
SQL 查询只允许 SELECT 语句，所有 SQL 必须加 LIMIT。
```

---

## 集成到 CI/CD 的 AI 提示词

```
请检查我的 Spring Boot 项目是否已正确集成 SnapAgent：

1. 确认 pom.xml 包含 snap-agent-spring-boot-2x-starter 依赖
2. 确认 application.yml 中 snap-agent.enabled=true
3. 确认 LLM api-key 通过环境变量注入，未硬编码
4. 确认存在名为 snapAgentReadOnlyDataSource 的 DataSource Bean
5. 确认 skills 目录下至少有一个 .md Skill 文件
6. 确认 SecurityConfig 放行了 /snap-agent/runs/*/stream 和 /snap-agent-internal/**
7. 确认 SqlGuard 测试通过 (mvn test -Dtest=SqlGuardTest)
```
