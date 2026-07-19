package cn.watsontech.snapagent.core.issue;

/**
 * SPI for verifying that a fix resolves an issue.
 */
public interface VerificationRunner {
    /**
     * Verify the fix for the given issue.
     *
     * @param issue the issue to verify
     * @return verification result, never null
     */
    VerificationResult verify(IssueClosure issue);
}
