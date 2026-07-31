package cn.watsontech.snapagent.boot2x.codegraph;

import cn.watsontech.snapagent.boot2x.tool.CodePathGuard;
import cn.watsontech.snapagent.core.codegraph.CodeGraph;
import cn.watsontech.snapagent.core.codegraph.CodeGraphBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link CodeGraphHotReloader}. Verifies that {@code .java} file
 * changes in the watched directory trigger a rebuild of the
 * {@link InMemoryCodeGraphIndex}.
 */
class CodeGraphHotReloaderTest {

    @TempDir
    Path tempDir;

    private CodePathGuard makeGuard(Path root) {
        return new CodePathGuard(root.toString(),
                Arrays.asList(".java", ".xml"),
                500, 512 * 1024);
    }

    /**
     * Creates a SimpleCodeGraphBuilder backed by {@code srcRoot} and an
     * InMemoryCodeGraphIndex seeded with an initial (possibly empty) build.
     */
    private IndexHolder buildIndex(Path srcRoot) {
        SimpleCodeGraphBuilder builder = new SimpleCodeGraphBuilder(
                makeGuard(srcRoot), Collections.<String>emptyList());
        CodeGraph graph = builder.build();
        InMemoryCodeGraphIndex index = new InMemoryCodeGraphIndex(graph);
        return new IndexHolder(builder, index);
    }

    @Test
    void shouldRebuildWhenJavaFileCreated(@TempDir Path watchRoot) throws Exception {
        // watchRoot is the source root; set up an empty src dir under it
        Path srcRoot = watchRoot.resolve("src");
        Files.createDirectories(srcRoot);

        IndexHolder holder = buildIndex(srcRoot);
        // Initially empty
        assertThat(holder.index.nodeCount()).isZero();

        CodeGraphHotReloader reloader = new CodeGraphHotReloader(
                srcRoot, holder.index, holder.builder, 500);
        reloader.start();

        // Create a new Java file
        Files.write(srcRoot.resolve("Hello.java"), (
                "public class Hello {\n"
                + "    public void greet() {\n"
                + "    }\n"
                + "}\n").getBytes());

        // Wait for the WatchService to detect and rebuild (macOS polls ~2s)
        awaitCondition(() -> holder.index.nodeCount() >= 2, 15000);
        assertThat(holder.index.nodeCount()).isGreaterThanOrEqualTo(2);
        assertThat(holder.index.findByName("Hello")).isNotEmpty();

        reloader.stop();
    }

    @Test
    void shouldRebuildWhenJavaFileAddedToNonEmptyDir(@TempDir Path watchRoot) throws Exception {
        Path srcRoot = watchRoot.resolve("src");
        Files.createDirectories(srcRoot);
        // Seed with an initial class
        Files.write(srcRoot.resolve("Counter.java"), (
                "public class Counter {\n"
                + "    public void step1() {\n"
                + "    }\n"
                + "}\n").getBytes());

        IndexHolder holder = buildIndex(srcRoot);
        int initialCount = holder.index.nodeCount();
        assertThat(initialCount).isGreaterThanOrEqualTo(2); // class + method

        CodeGraphHotReloader reloader = new CodeGraphHotReloader(
                srcRoot, holder.index, holder.builder, 500);
        reloader.start();

        // Add a second Java file — ENTRY_CREATE is reliably detected even on
        // macOS's polling WatchService (ENTRY_MODIFY for in-place rewrites is
        // unreliable on macOS, so we use a new file to trigger the rebuild).
        Files.write(srcRoot.resolve("Greeter.java"), (
                "public class Greeter {\n"
                + "    public void hello() {\n"
                + "    }\n"
                + "}\n").getBytes());

        // Wait for rebuild — node count should increase (class + method)
        awaitCondition(() -> holder.index.nodeCount() > initialCount, 15000);
        assertThat(holder.index.nodeCount()).isGreaterThan(initialCount);

        reloader.stop();
    }

    @Test
    void shouldHandleNonExistentDirGracefully() {
        Path nonexistent = tempRootNonExistent();
        IndexHolder holder = buildIndex(tempDir.resolve("src"));
        CodeGraphHotReloader reloader = new CodeGraphHotReloader(
                nonexistent, holder.index, holder.builder, 1000);
        // start should not throw even though the directory doesn't exist
        reloader.start();
        reloader.stop();
    }

    @Test
    void shouldRebuildH2IndexWhenJavaFileCreated(@TempDir Path watchRoot) throws Exception {
        Path srcRoot = watchRoot.resolve("src");
        Files.createDirectories(srcRoot);

        // Build an H2CodeGraphIndex (in-memory mode for test isolation)
        H2CodeGraphIndex h2Index = new H2CodeGraphIndex(
                "jdbc:h2:mem:hot-reload-test-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        SimpleCodeGraphBuilder builder = new SimpleCodeGraphBuilder(
                makeGuard(srcRoot), Collections.<String>emptyList());
        // Initial build — empty
        CodeGraph graph = builder.build();
        h2Index.loadGraph(graph);
        assertThat(h2Index.nodeCount()).isZero();

        CodeGraphHotReloader reloader = new CodeGraphHotReloader(
                srcRoot, h2Index, builder, 500);
        reloader.start();

        // Create a new Java file
        Files.write(srcRoot.resolve("Hello.java"), (
                "public class Hello {\n"
                + "    public void greet() {\n"
                + "    }\n"
                + "}\n").getBytes());

        // Wait for rebuild
        awaitCondition(() -> h2Index.nodeCount() >= 2, 15000);
        assertThat(h2Index.nodeCount()).isGreaterThanOrEqualTo(2);
        assertThat(h2Index.findByName("Hello")).isNotEmpty();

        reloader.stop();
        h2Index.close();
    }

    private Path tempRootNonExistent() {
        return tempDir.resolve("does-not-exist-" + System.nanoTime());
    }

    /**
     * Polls {@code assertion} every 100ms until it returns true or the timeout
     * elapses. Required because macOS WatchService uses polling.
     */
    private static void awaitCondition(java.util.function.BooleanSupplier assertion, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (assertion.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        // Final check — let the assertion throw if it still fails
        assertThat(assertion.getAsBoolean())
                .as("condition not met within %d ms", timeoutMs)
                .isTrue();
    }

    /** Small holder so tests can reference both the builder and the index. */
    private static final class IndexHolder {
        final CodeGraphBuilder builder;
        final InMemoryCodeGraphIndex index;

        IndexHolder(CodeGraphBuilder builder, InMemoryCodeGraphIndex index) {
            this.builder = builder;
            this.index = index;
        }
    }
}
