package cn.watsontech.snapagent.core.closure;

/**
 * SPI for running verification after a fix has been applied.
 *
 * <p>Implementations re-run the diagnostic query or check metrics to confirm
 * the issue has been resolved.</p>
 */
public interface VerificationRunner {

    /**
     * Run verification for the given issue.
     *
     * @param issue the issue to verify (should have taskId for re-running)
     * @return the verification result (passed/failed + before/after status)
     */
    VerificationResult verify(IssueClosure issue);
}
