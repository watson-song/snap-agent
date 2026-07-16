package cn.watsontech.snapagent.core.issue;

/**
 * 问题闭环状态机。
 *
 * <p>状态流转:
 * <pre>
 * DIAGNOSED → SOLUTION_PROPOSED → FIX_IN_PROGRESS → VERIFIED → CLOSED
 * </pre>
 * </p>
 *
 * <ul>
 *   <li>{@link #DIAGNOSED} — 已诊断, 待出方案</li>
 *   <li>{@link #SOLUTION_PROPOSED} — 方案已生成, 待创建 Issue</li>
 *   <li>{@link #FIX_IN_PROGRESS} — Issue 已创建, 修复中</li>
 *   <li>{@link #VERIFIED} — 已验证修复生效</li>
 *   <li>{@link #CLOSED} — 已关闭, 经验已沉淀</li>
 * </ul>
 */
public enum IssueStatus {
    /** 已诊断, 待出方案。 */
    DIAGNOSED,
    /** 方案已生成, 待创建 Issue。 */
    SOLUTION_PROPOSED,
    /** Issue 已创建, 修复中。 */
    FIX_IN_PROGRESS,
    /** 已验证修复生效。 */
    VERIFIED,
    /** 已关闭, 经验已沉淀。 */
    CLOSED
}
