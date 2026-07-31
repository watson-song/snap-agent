package cn.watsontech.snapagent.core.issue;

import cn.watsontech.snapagent.core.vectorstore.Document;

/**
 * SPI for reviewing knowledge sedimentation before it enters the VectorStore.
 *
 * <p>Implementations can implement human-in-the-loop review, LLM-based quality
 * check, or any other acceptance gate. The reviewer is called by
 * {@code KnowledgeSedimentationService} after extracting a {@link Document}
 * from an {@link IssueClosure} but before embedding and writing to the
 * VectorStore.</p>
 *
 * <p>P1-7: enables quality control over what enters the knowledge base,
 * preventing low-quality or incorrect sedimentations from polluting search
 * results.</p>
 *
 * <p>Known implementations:</p>
 * <ul>
 *   <li>{@code AcceptAllSedimentationReviewer} — always accepts (default, boot2x starter)</li>
 * </ul>
 */
public interface SedimentationReviewer {

    /**
     * Review a sedimented document.
     *
     * @param document the document extracted from the issue closure
     * @param issue    the originating issue closure
     * @return {@code true} if the document should be accepted into the VectorStore,
     *         {@code false} to reject and skip it
     */
    boolean review(Document document, IssueClosure issue);

    /**
     * Reviewer name (e.g., "always-accept", "llm-quality-check").
     *
     * @return the reviewer name
     */
    String name();
}
