package cn.watsontech.snapagent.core.vcs;

/**
 * Represents a single file change to be committed via VcsClient.
 * Immutable value object.
 */
public final class FileChange {
    private final String filePath;
    private final String content;
    private final String action; // CREATE, UPDATE, DELETE

    public FileChange(String filePath, String content, String action) {
        this.filePath = filePath;
        this.content = content;
        this.action = action;
    }

    public String getFilePath() { return filePath; }
    public String getContent() { return content; }
    public String getAction() { return action; }

    public static FileChange create(String filePath, String content) {
        return new FileChange(filePath, content, "CREATE");
    }
    public static FileChange update(String filePath, String content) {
        return new FileChange(filePath, content, "UPDATE");
    }
    public static FileChange delete(String filePath) {
        return new FileChange(filePath, null, "DELETE");
    }

    @Override
    public String toString() {
        return "FileChange{filePath='" + filePath + "', action=" + action + "}";
    }
}
