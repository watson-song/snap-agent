package cn.watsontech.snapagent.core.issue;

/**
 * 问题闭环状态机。
 *
 * <p>状态流转:
 * <pre>
 * DIAGNOSED → SOLUTION_PROPOSED → ISSUE_CREATED → FIX_IN_PROGRESS → VERIFIED → CLOSED
 * </pre>
 * 失败终态: {@link #FAILED} (验证不通过或修复无法完成的错误终态)
 * </p>
 *
 * <ul>
 *   <li>{@link #DIAGNOSED} — 已诊断, 待出方案</li>
 *   <li>{@link #SOLUTION_PROPOSED} — 方案已生成, 待创建 Issue</li>
 *   <li>{@link #ISSUE_CREATED} — 外部 Issue 已创建, 待开始修复</li>
 *   <li>{@link #FIX_IN_PROGRESS} — 修复中</li>
 *   <li>{@link #VERIFIED} — 已验证修复生效</li>
 *   <li>{@link #CLOSED} — 已关闭, 经验已沉淀</li>
 *   <li>{@link #FAILED} — 失败终态 (修复无法完成或验证不通过)</li>
 * </ul>
 */
public enum IssueStatus {
    /** 已诊断, 待出方案。 */
    DIAGNOSED,
    /** 方案已生成, 待创建 Issue。 */
    SOLUTION_PROPOSED,
    /** 外部 Issue 已创建, 待开始修复。 */
    ISSUE_CREATED,
    /** 修复中。 */
    FIX_IN_PROGRESS,
    /** 已验证修复生效。 */
    VERIFIED,
    /** 已关闭, 经验已沉淀。 */
    CLOSED,
    /** 失败终态 (修复无法完成或验证不通过)。 */
    FAILED
}
