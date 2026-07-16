package cn.watsontech.snapagent.core.closure;

/**
 * Issue lifecycle status within the Q&A closed loop.
 */
public enum IssueStatus {
    DIAGNOSED,
    SOLUTION_PROPOSED,
    ISSUE_CREATED,
    FIX_IN_PROGRESS,
    VERIFIED,
    CLOSED,
    FAILED
}
