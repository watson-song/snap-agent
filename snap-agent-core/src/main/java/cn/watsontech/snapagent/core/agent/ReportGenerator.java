package cn.watsontech.snapagent.core.agent;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Converts a list of {@link TranscriptEvent}s into a downloadable Markdown report.
 */
public final class ReportGenerator {

    private static final SimpleDateFormat DATE_FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private ReportGenerator() {}

    public static String generate(String title, List<TranscriptEvent> events) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(title != null ? title : "诊断报告").append("\n\n");
        sb.append("> 生成时间: ").append(DATE_FMT.format(new Date())).append("\n\n---\n\n");

        if (events == null || events.isEmpty()) {
            sb.append("无诊断记录。\n");
            return sb.toString();
        }

        for (TranscriptEvent e : events) {
            String time = DATE_FMT.format(new Date(e.getTimestamp()));
            switch (e.getType()) {
                case TranscriptEvent.TYPE_THOUGHT:
                    sb.append("### 💭 思考 (").append(time).append(")\n\n")
                      .append(e.getText()).append("\n\n");
                    break;
                case TranscriptEvent.TYPE_TOOL_CALL:
                    sb.append("### 🔧 工具调用 (").append(time).append(")\n\n")
                      .append("- 工具: `").append(e.getData().get("name")).append("`\n")
                      .append("- 参数: ").append(formatArgs(e.getData().get("args"))).append("\n\n");
                    break;
                case TranscriptEvent.TYPE_TOOL_RESULT:
                    sb.append("### 📊 工具结果 (").append(time).append(")\n\n")
                      .append("- 行数: ").append(e.getData().get("rowCount")).append("\n")
                      .append("- 耗时: ").append(e.getData().get("durationMs")).append("ms\n");
                    if (e.getData().containsKey("content")) {
                        sb.append("- 内容预览:\n```\n")
                          .append(e.getData().get("content")).append("\n```\n");
                    }
                    if (e.getData().containsKey("error")) {
                        sb.append("- ❌ 错误: ").append(e.getData().get("error")).append("\n");
                    }
                    sb.append("\n");
                    break;
                case TranscriptEvent.TYPE_DONE:
                    sb.append("### ✅ 完成 (").append(time).append(")\n\n")
                      .append("- 状态: **").append(e.getData().get("status")).append("**\n");
                    if (e.getData().get("report") != null) {
                        sb.append("- 报告: ").append(e.getData().get("report")).append("\n");
                    }
                    sb.append("\n");
                    break;
                case TranscriptEvent.TYPE_ERROR:
                    sb.append("### ❌ 错误 (").append(time).append(")\n\n")
                      .append(e.getText()).append("\n\n");
                    break;
                default:
                    sb.append("### ").append(e.getType()).append(" (").append(time).append(")\n\n");
                    if (e.getText() != null) sb.append(e.getText()).append("\n\n");
            }
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static String formatArgs(Object args) {
        if (args == null) return "无";
        if (args instanceof Map) {
            StringBuilder sb = new StringBuilder();
            Map<String, Object> map = (Map<String, Object>) args;
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                sb.append("`").append(entry.getKey()).append("`=");
                String val = String.valueOf(entry.getValue());
                if (val.length() > 100) val = val.substring(0, 100) + "...";
                sb.append(val).append(" ");
            }
            return sb.toString().trim();
        }
        return String.valueOf(args);
    }
}
