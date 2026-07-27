package cn.watsontech.snapagent.boot2x.fix;

/**
 * ThreadLocal holder for the current FixContext.
 * Set by FixExecutionService before running the agent; cleared after.
 * File tools (FileWriteTool, FileEditTool) access the context via this holder.
 */
public class FixContextHolder {

    private final ThreadLocal<FixContext> holder = new ThreadLocal<FixContext>();

    public void set(FixContext ctx) {
        holder.set(ctx);
    }

    public FixContext get() {
        return holder.get();
    }

    public void clear() {
        holder.remove();
    }
}
