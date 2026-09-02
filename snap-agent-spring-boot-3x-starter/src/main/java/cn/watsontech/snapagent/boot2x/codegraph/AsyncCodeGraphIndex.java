package cn.watsontech.snapagent.boot2x.codegraph;

import cn.watsontech.snapagent.core.codegraph.CodeGraph;
import cn.watsontech.snapagent.core.codegraph.CodeGraphBuilder;
import cn.watsontech.snapagent.core.codegraph.CodeGraphEdge;
import cn.watsontech.snapagent.core.codegraph.CodeGraphNode;
import cn.watsontech.snapagent.core.codegraph.CodeGraphIndex;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * {@link CodeGraphIndex} that builds its delegate asynchronously in a daemon thread.
 *
 * <p>The constructor starts a background daemon thread that calls
 * {@link CodeGraphBuilder#build()} and wraps the result in an
 * {@link InMemoryCodeGraphIndex}. Query methods block (via a
 * {@link CountDownLatch}) until the build completes, then delegate to the
 * underlying index. If the build fails, all queries return empty results.</p>
 *
 * <p>This prevents the code graph build (which can be slow for large projects)
 * from blocking application startup.</p>
 */
public class AsyncCodeGraphIndex implements CodeGraphIndex {

    private final CodeGraphBuilder builder;
    private volatile CodeGraphIndex delegate;
    private final CountDownLatch latch = new CountDownLatch(1);

    /**
     * Construct and start the async build thread.
     *
     * @param builder the code graph builder to execute asynchronously
     */
    public AsyncCodeGraphIndex(CodeGraphBuilder builder) {
        this.builder = builder;
        Thread buildThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    CodeGraph graph = AsyncCodeGraphIndex.this.builder.build();
                    delegate = new InMemoryCodeGraphIndex(graph);
                } finally {
                    latch.countDown();
                }
            }
        }, "codegraph-builder");
        buildThread.setDaemon(true);
        buildThread.start();
    }

    /**
     * Blocks until the background build thread has completed (success or failure).
     * If the thread is interrupted, the interrupt flag is restored and the method
     * returns immediately (queries will see a possibly-null delegate).
     */
    private void awaitBuild() {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public List<CodeGraphNode> findByName(String namePattern) {
        awaitBuild();
        return delegate != null
                ? delegate.findByName(namePattern)
                : Collections.<CodeGraphNode>emptyList();
    }

    @Override
    public List<CodeGraphEdge> getOutgoingEdges(String nodeId) {
        awaitBuild();
        return delegate != null
                ? delegate.getOutgoingEdges(nodeId)
                : Collections.<CodeGraphEdge>emptyList();
    }

    @Override
    public List<CodeGraphEdge> getIncomingEdges(String nodeId) {
        awaitBuild();
        return delegate != null
                ? delegate.getIncomingEdges(nodeId)
                : Collections.<CodeGraphEdge>emptyList();
    }

    @Override
    public List<CodeGraphNode> findCallChain(String methodId, int maxDepth) {
        awaitBuild();
        return delegate != null
                ? delegate.findCallChain(methodId, maxDepth)
                : Collections.<CodeGraphNode>emptyList();
    }

    @Override
    public List<CodeGraphNode> findReverseCallChain(String methodId, int maxDepth) {
        awaitBuild();
        return delegate != null
                ? delegate.findReverseCallChain(methodId, maxDepth)
                : Collections.<CodeGraphNode>emptyList();
    }

    @Override
    public List<CodeGraphNode> findImpactScope(String nodeId, int maxDepth) {
        awaitBuild();
        return delegate != null
                ? delegate.findImpactScope(nodeId, maxDepth)
                : Collections.<CodeGraphNode>emptyList();
    }

    @Override
    public CodeGraphNode getNode(String id) {
        awaitBuild();
        return delegate != null ? delegate.getNode(id) : null;
    }

    @Override
    public int nodeCount() {
        awaitBuild();
        return delegate != null ? delegate.nodeCount() : 0;
    }

    @Override
    public void rebuild(CodeGraphBuilder builder) {
        awaitBuild();
        if (delegate != null) {
            delegate.rebuild(builder);
        }
    }
}
