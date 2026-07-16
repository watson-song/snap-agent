package cn.watsontech.snapagent.core.closure;

/**
 * A single candidate solution option within a {@link SolutionSuggestion}.
 */
public class SolutionOption {

    private String id;
    private String title;
    private String description;
    private String effort;       // "low", "medium", "high"
    private boolean temporary;   // true = quick fix, false = long-term fix

    public SolutionOption() {
    }

    public SolutionOption(String id, String title, String description,
                          String effort, boolean temporary) {
        this.id = id;
        this.title = title;
        this.description = description;
        this.effort = effort;
        this.temporary = temporary;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getEffort() { return effort; }
    public void setEffort(String effort) { this.effort = effort; }
    public boolean isTemporary() { return temporary; }
    public void setTemporary(boolean temporary) { this.temporary = temporary; }

    @Override
    public String toString() {
        return "SolutionOption{id='" + id + "', title='" + title
                + "', effort=" + effort + ", temporary=" + temporary + "}";
    }
}
