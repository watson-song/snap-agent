package cn.watsontech.snapagent.boot2x.domain;

import cn.watsontech.snapagent.core.domain.DomainKnowledge;
import cn.watsontech.snapagent.core.domain.DomainKnowledgeIndex;
import cn.watsontech.snapagent.core.tool.Tool;
import cn.watsontech.snapagent.core.tool.ToolParam;

import java.util.List;

/**
 * Domain knowledge tools exposed via {@code @Tool} annotation methods.
 *
 * <p>Provides tools for the Agent to query domain knowledge:</p>
 * <ul>
 *   <li>{@code lookup_concept} — look up a business concept by name</li>
 *   <li>{@code find_by_table} — reverse lookup: which concepts involve a given DB table</li>
 *   <li>{@code find_by_class} — reverse lookup: which concepts involve a given Java class</li>
 *   <li>{@code list_concepts} — list all registered domain concepts</li>
 * </ul>
 */
public class DomainKnowledgeTools {

    private final DomainKnowledgeIndex index;

    public DomainKnowledgeTools(DomainKnowledgeIndex index) {
        this.index = index;
    }

    @Tool(name = "lookup_concept",
          description = "按名称查询业务领域概念。返回概念详情，包括涉及的数据库表、Java类、入口方法、业务规则和已知陷阱。用于理解业务概念与代码的映射关系。")
    public String lookupConcept(
            @ToolParam(description = "概念名称，如 '调拨计划' 或 '补货策略'") String conceptName) {
        if (conceptName == null || conceptName.trim().isEmpty()) {
            return "请提供概念名称。";
        }

        DomainKnowledge dk = index.findByName(conceptName.trim());
        if (dk == null) {
            return "未找到概念: '" + conceptName + "'。可用概念请使用 list_concepts 查看。";
        }

        return dk.toSummary();
    }

    @Tool(name = "find_by_table",
          description = "按数据库表名反查业务概念。输入表名（如 drp_allocation_plan），返回涉及该表的所有业务概念。用于从数据层面理解业务。")
    public String findByTable(
            @ToolParam(description = "数据库表名，如 'drp_allocation_plan'") String tableName) {
        if (tableName == null || tableName.trim().isEmpty()) {
            return "请提供数据库表名。";
        }

        List<DomainKnowledge> results = index.findByTable(tableName.trim());
        if (results.isEmpty()) {
            return "未找到涉及表 '" + tableName + "' 的业务概念。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("涉及表 '").append(tableName).append("' 的业务概念 (").append(results.size()).append(" 个):\n\n");
        for (DomainKnowledge dk : results) {
            sb.append("• ").append(dk.getName()).append("\n");
            if (!dk.getServices().isEmpty()) {
                sb.append("  相关类: ").append(join(dk.getServices())).append("\n");
            }
        }
        return sb.toString();
    }

    @Tool(name = "find_by_class",
          description = "按Java类名反查业务概念。输入类名（如 AllocationPlanService），返回涉及该类的所有业务概念。用于从代码层面理解业务。")
    public String findByClass(
            @ToolParam(description = "Java类名（简单名），如 'AllocationPlanService'") String className) {
        if (className == null || className.trim().isEmpty()) {
            return "请提供Java类名。";
        }

        List<DomainKnowledge> results = index.findByService(className.trim());
        if (results.isEmpty()) {
            return "未找到涉及类 '" + className + "' 的业务概念。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("涉及类 '").append(className).append("' 的业务概念 (").append(results.size()).append(" 个):\n\n");
        for (DomainKnowledge dk : results) {
            sb.append("• ").append(dk.getName()).append("\n");
            if (!dk.getTables().isEmpty()) {
                sb.append("  相关表: ").append(join(dk.getTables())).append("\n");
            }
            if (!dk.getEntryPoints().isEmpty()) {
                sb.append("  入口方法: ").append(join(dk.getEntryPoints())).append("\n");
            }
        }
        return sb.toString();
    }

    @Tool(name = "list_concepts",
          description = "列出所有已注册的业务领域概念。返回概念名称、涉及的表数量和类数量概览。")
    public String listConcepts() {
        List<DomainKnowledge> all = index.findAll();
        if (all.isEmpty()) {
            return "暂无已注册的业务领域概念。请在 domain-knowledge/ 目录下创建 Markdown 知识文件。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("已注册的业务领域概念 (").append(all.size()).append(" 个):\n\n");
        for (DomainKnowledge dk : all) {
            sb.append("• ").append(dk.getName());
            sb.append(" (表:").append(dk.getTables().size());
            sb.append(", 类:").append(dk.getServices().size());
            if (!dk.getTags().isEmpty()) {
                sb.append(", 标签:").append(join(dk.getTags()));
            }
            sb.append(")\n");
        }
        return sb.toString();
    }

    private String join(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(list.get(i));
        }
        return sb.toString();
    }
}
