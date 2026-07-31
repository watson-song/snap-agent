package cn.watsontech.snapagent.boot2x.codegraph;

import cn.watsontech.snapagent.core.codegraph.CodeGraph;
import cn.watsontech.snapagent.core.codegraph.CodeGraphBuilder;
import cn.watsontech.snapagent.core.codegraph.CodeGraphEdge;
import cn.watsontech.snapagent.core.codegraph.CodeGraphNode;
import cn.watsontech.snapagent.core.codegraph.CodeGraphIndex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link AsyncCodeGraphIndex} — async code graph build with latch-based
 * query gating.
 *
 * <p>Verifies that the constructor does not block, queries return results after
 * the build completes, and queries return empty results when the build fails.</p>
 */
@DisplayName("AsyncCodeGraphIndex — async build with latch-gated queries")
class AsyncCodeGraphIndexTest {

    private CodeGraphNode methodNode(String id, String name) {
        return new CodeGraphNode(id, CodeGraphNode.NodeType.METHOD, name,
                "com.test", id, "void", "Foo.java", 10);
    }

    /**
     * Constructor should return immediately even when the builder is slow.
     * The build happens in a background daemon thread.
     */
    @Test
    @DisplayName("shouldNotBlockConstructor — constructor returns immediately with slow builder")
    void shouldNotBlockConstructor() {
        CodeGraphBuilder slowBuilder = new CodeGraphBuilder() {
            @Override
            public CodeGraph build() {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new CodeGraph(Collections.<CodeGraphNode>emptyList(),
                        Collections.emptyList());
            }

            @Override
            public String type() {
                return "slow";
            }
        };

        long start = System.currentTimeMillis();
        // Constructor should return immediately, not wait 2 seconds
        new AsyncCodeGraphIndex(slowBuilder);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isLessThan(1000L);
    }

    /**
     * After the build completes, queries should return real results from the delegate.
     */
    @Test
    @DisplayName("shouldReturnResultsAfterBuildCompletes — queries work after build")
    void shouldReturnResultsAfterBuildCompletes() {
        CodeGraphNode nodeA = methodNode("com.test.A#a()", "a");
        CodeGraphNode nodeB = methodNode("com.test.B#b()", "b");
        CodeGraphBuilder builder = new CodeGraphBuilder() {
            @Override
            public CodeGraph build() {
                return new CodeGraph(Arrays.asList(nodeA, nodeB),
                        Collections.emptyList());
            }

            @Override
            public String type() {
                return "test";
            }
        };

        AsyncCodeGraphIndex index = new AsyncCodeGraphIndex(builder);

        // findByName will await build completion via latch, then delegate
        List<CodeGraphNode> results = index.findByName("a");

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).getName()).isEqualTo("a");
        assertThat(index.nodeCount()).isEqualTo(2);
        assertThat(index.getNode("com.test.B#b()")).isNotNull();
        assertThat(index.getNode("com.test.B#b()").getName()).isEqualTo("b");
    }

    /**
     * If the build fails (throws), the delegate stays null and all queries return
     * empty results / null / 0 instead of propagating the exception.
     */
    @Test
    @DisplayName("shouldReturnEmptyBeforeBuildCompletes — failed build returns empty results")
    void shouldReturnEmptyBeforeBuildCompletes() {
        CodeGraphBuilder failingBuilder = new CodeGraphBuilder() {
            @Override
            public CodeGraph build() {
                throw new RuntimeException("build failed");
            }

            @Override
            public String type() {
                return "failing";
            }
        };

        AsyncCodeGraphIndex index = new AsyncCodeGraphIndex(failingBuilder);

        // The latch counts down in finally, but delegate is null
        // All queries should return empty/null/0
        assertThat(index.findByName("anything")).isEmpty();
        assertThat(index.nodeCount()).isEqualTo(0);
        assertThat(index.getNode("x")).isNull();
        assertThat(index.getOutgoingEdges("x")).isEmpty();
        assertThat(index.getIncomingEdges("x")).isEmpty();
        assertThat(index.findCallChain("x", 5)).isEmpty();
        assertThat(index.findReverseCallChain("x", 5)).isEmpty();
        assertThat(index.findImpactScope("x", 5)).isEmpty();
    }

    /**
     * rebuild() should delegate to the underlying index after the initial build.
     */
    @Test
    @DisplayName("shouldDelegateRebuildAfterBuild — rebuild replaces graph data")
    void shouldDelegateRebuildAfterBuild() {
        CodeGraphNode nodeA = methodNode("com.test.A#a()", "a");
        CodeGraph initialGraph = new CodeGraph(Collections.singletonList(nodeA),
                Collections.<CodeGraphEdge>emptyList());
        CodeGraphNode nodeB = methodNode("com.test.B#b()", "b");
        CodeGraph rebuiltGraph = new CodeGraph(Collections.singletonList(nodeB),
                Collections.<CodeGraphEdge>emptyList());

        CodeGraphBuilder builder = new CodeGraphBuilder() {
            @Override
            public CodeGraph build() { return initialGraph; }
            @Override
            public String type() { return "test"; }
        };

        AsyncCodeGraphIndex index = new AsyncCodeGraphIndex(builder);
        // Wait for initial build
        assertThat(index.findByName("a")).isNotEmpty();
        assertThat(index.nodeCount()).isEqualTo(1);

        // Now rebuild with a different builder
        CodeGraphBuilder rebuilder = new CodeGraphBuilder() {
            @Override
            public CodeGraph build() { return rebuiltGraph; }
            @Override
            public String type() { return "rebuilder"; }
        };
        index.rebuild(rebuilder);

        // Old node gone, new node present
        assertThat(index.findByName("a")).isEmpty();
        assertThat(index.findByName("b")).isNotEmpty();
        assertThat(index.nodeCount()).isEqualTo(1);
    }
}
