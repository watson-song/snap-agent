package cn.watsontech.snapagent.core.issue;

/**
 * A single acceptance criterion for verifying a fix.
 * Immutable value object.
 */
public final class AcceptanceCriterion {
    private final String id;
    private final String description;
    private final String verification; // SQL or command
    private final String expected;      // "> 0", "= true", "contains xxx"
    private final String tool;          // "mysql_query", etc.

    public AcceptanceCriterion(String id, String description, String verification,
                               String expected, String tool) {
        this.id = id;
        this.description = description;
        this.verification = verification;
        this.expected = expected;
        this.tool = tool;
    }

    public String getId() { return id; }
    public String getDescription() { return description; }
    public String getVerification() { return verification; }
    public String getExpected() { return expected; }
    public String getTool() { return tool; }

    @Override
    public String toString() {
        return "AcceptanceCriterion{id='" + id + "', description='" + description + "'}";
    }
}
