package cn.watsontech.snapagent.boot2x.fix;

import cn.watsontech.snapagent.core.tool.Tool;
import cn.watsontech.snapagent.core.tool.ToolParam;
import cn.watsontech.snapagent.core.tool.ToolCallback;
import cn.watsontech.snapagent.core.tool.ToolResult;

import java.util.Map;

/**
 * LLM-callable tool for targeted string replacement in existing files.
 * Similar to Claude Code's Edit tool.
 */
public class FileEditTool implements ToolCallback {

    private final FixContextHolder holder;
    private final FixGuard guard;

    public FileEditTool(FixContextHolder holder, FixGuard guard) {
        this.holder = holder;
        this.guard = guard;
    }

    @Tool(name = "file_edit",
          description = "Replaces a specific string in a file. Provide oldString (exact match) and newString.")
    public ToolResult edit(
            @ToolParam(description = "File path relative to project root") String filePath,
            @ToolParam(description = "Exact string to find in the file") String oldString,
            @ToolParam(description = "String to replace oldString with") String newString) {

        long start = System.currentTimeMillis();
        FixContext ctx = holder.get();
        if (ctx == null) {
            return ToolResult.error("File modification is only available during fix execution",
                    System.currentTimeMillis() - start);
        }

        String currentContent = ctx.getFileContent(filePath);
        if (currentContent == null) {
            return ToolResult.error("File not found: " + filePath,
                    System.currentTimeMillis() - start);
        }

        int idx = currentContent.indexOf(oldString);
        if (idx < 0) {
            return ToolResult.error("oldString not found in " + filePath,
                    System.currentTimeMillis() - start);
        }

        String newContent = currentContent.substring(0, idx)
                + newString
                + currentContent.substring(idx + oldString.length());

        try {
            guard.validate(filePath, newContent, "UPDATE", ctx.size());
            ctx.addChange(filePath, newContent, "UPDATE");
            String summary = oldString.length() > 50
                    ? oldString.substring(0, 50) + "..." : oldString;
            return ToolResult.success("File edited: " + filePath
                    + "\n- " + summary + " → " + (newString.length() > 50
                    ? newString.substring(0, 50) + "..." : newString),
                    0, System.currentTimeMillis() - start);
        } catch (RuntimeException e) {
            return ToolResult.error("Guard rejected: " + e.getMessage(),
                    System.currentTimeMillis() - start);
        }
    }

    @Override
    public ToolResult execute(Map<String, Object> input, Object context) {
        String filePath = input.get("filePath") != null ? input.get("filePath").toString() : null;
        String oldString = input.get("oldString") != null ? input.get("oldString").toString() : null;
        String newString = input.get("newString") != null ? input.get("newString").toString() : null;
        return edit(filePath, oldString, newString);
    }

    @Override
    public String getName() { return "file_edit"; }

    @Override
    public String getDescription() { return "Replaces a specific string in a file."; }
}
