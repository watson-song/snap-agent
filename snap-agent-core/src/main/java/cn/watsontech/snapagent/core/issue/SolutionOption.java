package cn.watsontech.snapagent.core.issue;

/**
 * Single candidate fix option within a {@link SolutionSuggestion}.
 *
 * <p>Immutable value object.</p>
 */
public final class SolutionOption {
    private final String id;
    private final String title;
    private final String description;
    private final String effort;      // "low", "medium", "high"
    private final boolean temporary;

    public SolutionOption(String id, String title, String description, String effort, boolean temporary) {
        this.id = id;
        this.title = title;
        this.description = description;
        this.effort = effort;
        this.temporary = temporary;
    }

    public String getId() { return id; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getEffort() { return effort; }
    public boolean isTemporary() { return temporary; }

    @Override
    public String toString() {
        return "SolutionOption{id='" + id + "', title='" + title + "', effort=" + effort + ", temporary=" + temporary + "}";
    }
}
