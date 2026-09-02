package cn.watsontech.snapagent.boot2x.knowledge;

import cn.watsontech.snapagent.core.issue.IssueClosure;
import cn.watsontech.snapagent.core.issue.SedimentationReviewer;
import cn.watsontech.snapagent.core.vectorstore.Document;

/**
 * Default {@link SedimentationReviewer} that always accepts documents.
 *
 * <p>This is the no-op quality gate — every document that passes extraction
 * is written to the VectorStore. This preserves the original behavior before
 * the reviewer SPI was introduced.</p>
 *
 * <p>P1-7: default implementation. To add a quality gate, implement
 * {@link SedimentationReviewer} and pass it to
 * {@code KnowledgeSedimentationService}.</p>
 */
public class AcceptAllSedimentationReviewer implements SedimentationReviewer {

    @Override
    public boolean review(Document document, IssueClosure issue) {
        return true;
    }

    @Override
    public String name() {
        return "always-accept";
    }
}
