package cn.watsontech.snapagent.core.graph.execution;

import cn.watsontech.snapagent.core.agent.TaskStatus;

public class TaskResult {
    private final TaskStatus status;
    private final String report;

    public TaskResult(TaskStatus status, String report) {
        this.status = status;
        this.report = report;
    }

    public TaskStatus getStatus() { return status; }
    public String getReport() { return report; }
}
