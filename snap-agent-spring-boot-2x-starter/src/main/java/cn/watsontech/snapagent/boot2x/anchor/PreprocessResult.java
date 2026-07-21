package cn.watsontech.snapagent.boot2x.anchor;

/**
 * Handle returned from {@link AnchorOrchestrator#preprocess(AnchorContext, String)}
 * representing the started background preprocess job.
 *
 * <p>The preprocessId is used by the client to correlate subsequent
 * {@code POST /runs} requests with the precomputed summary + skillId.</p>
 */
public class PreprocessResult {

    private final String preprocessId;

    public PreprocessResult(String preprocessId) {
        this.preprocessId = preprocessId;
    }

    public String getPreprocessId() {
        return preprocessId;
    }
}
