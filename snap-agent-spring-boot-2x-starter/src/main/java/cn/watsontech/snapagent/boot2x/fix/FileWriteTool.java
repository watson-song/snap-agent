package cn.watsontech.snapagent.boot2x.fix;

import cn.watsontech.snapagent.core.tool.Tool;
import cn.watsontech.snapagent.core.tool.ToolParam;
import cn.watsontech.snapagent.core.tool.ToolCallback;
import cn.watsontech.snapagent.core.tool.ToolResult;

import java.util.Map;

/**
 * LLM-callable tool for creating/overwriting files during fix execution.
 * Changes are tracked in FixContext (in-memory), not written to disk.
 * Guard validates every change.
 */
public class FileWriteTool implements ToolCallback {

    private final FixContextHolder holder;
    private final FixGuard guard;

    public FileWriteTool(FixContextHolder holder, FixGuard guard) {
        this.holder = holder;
        this.guard = guard;
    }

    @Tool(name = "file_write",
          description = "Creates or overwrites a file with the given content. Use for new files only.")
    public ToolResult write(
            @ToolParam(description = "File path relative to project root") String filePath,
            @ToolParam(description = "Full file content") String content) {

        long start = System.currentTimeMillis();
        FixContext ctx = holder.get();
        if (ctx == null) {
            return ToolResult.error("File modification is only available during fix execution",
                    System.currentTimeMillis() - start);
        }

        try {
            guard.validate(filePath, content, "CREATE", ctx.size());
            ctx.addChange(filePath, content, "CREATE");
            return ToolResult.success("File written: " + filePath + " (" + content.length() + " chars)",
                    0, System.currentTimeMillis() - start);
        } catch (RuntimeException e) {
            return ToolResult.error("Guard rejected: " + e.getMessage(),
                    System.currentTimeMillis() - start);
        }
    }

    @Override
    public ToolResult execute(Map<String, Object> input, Object context) {
        String filePath = input.get("filePath") != null ? input.get("filePath").toString() : null;
        String content = input.get("content") != null ? input.get("content").toString() : null;
        return write(filePath, content);
    }

    @Override
    public String getName() { return "file_write"; }

    @Override
    public String getDescription() { return "Creates or overwrites a file. Use for new files only."; }
}
