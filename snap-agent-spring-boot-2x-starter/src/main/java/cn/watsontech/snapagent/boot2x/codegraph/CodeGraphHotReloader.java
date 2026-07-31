package cn.watsontech.snapagent.boot2x.codegraph;

import cn.watsontech.snapagent.core.codegraph.CodeGraphBuilder;
import cn.watsontech.snapagent.core.codegraph.CodeGraphIndex;
import com.sun.nio.file.SensitivityWatchEventModifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Watches the project source root for {@code .java} file changes and triggers
 * a rebuild of the code graph via {@link CodeGraphIndex#rebuild(CodeGraphBuilder)}.
 *
 * <p>The watcher recursively registers all sub-directories under the project
 * root (the JDK {@code WatchService} only watches a single directory level, so
 * we must register every directory we want to observe). When a {@code .java}
 * file is created, modified, or deleted, the graph is rebuilt from scratch.</p>
 *
 * <p>Only {@code .java} files trigger a rebuild. The watcher runs on a daemon
 * thread so it does not prevent JVM shutdown. If the watch directory does not
 * exist at {@link #start()} time, the reloader logs a warning and becomes a
 * no-op (safe to call {@link #stop()} afterwards).</p>
 *
 * <p><b>macOS note:</b> the JDK's {@code PollingWatchService} on macOS defaults
 * to a 10-second polling interval ({@code MEDIUM} sensitivity). We register
 * with {@link SensitivityWatchEventModifier#HIGH} (2&nbsp;seconds) for faster
 * detection. On Linux/Windows the modifier is silently ignored because those
 * platforms use kernel-level event mechanisms (inotify / ReadDirectoryChangesW)
 * that deliver events instantly.</p>
 */
public class CodeGraphHotReloader {

    private static final Logger log = LoggerFactory.getLogger(CodeGraphHotReloader.class);

    private final Path watchRoot;
    private final CodeGraphIndex index;
    private final CodeGraphBuilder builder;
    private final long pollIntervalMs;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread watchThread;
    private WatchService watchService;

    /**
     * @param watchRoot      the project source root to watch recursively
     * @param index          the code graph index to rebuild (must support {@link CodeGraphIndex#rebuild})
     * @param builder        the code graph builder used to rebuild the graph
     * @param pollIntervalMs poll interval in milliseconds (used when no events
     *                       arrive; the watch thread blocks for this duration)
     */
    public CodeGraphHotReloader(Path watchRoot, CodeGraphIndex index,
                                CodeGraphBuilder builder, long pollIntervalMs) {
        this.watchRoot = watchRoot;
        this.index = index;
        this.builder = builder;
        this.pollIntervalMs = pollIntervalMs;
    }

    /**
     * Starts watching the directory tree. If the root does not exist or cannot
     * be registered, a warning is logged and the reloader becomes a no-op.
     * Calling {@link #start()} more than once without {@link #stop()} is a no-op.
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            watchService = FileSystems.getDefault().newWatchService();
            if (!Files.exists(watchRoot)) {
                log.warn("Code graph watch dir does not exist, hot reload disabled: {}", watchRoot);
                return;
            }
            registerRecursive(watchRoot);
        } catch (IOException e) {
            log.warn("Failed to start code graph hot reloader: {}", e.getMessage());
            return;
        }
        watchThread = new Thread(this::watchLoop, "code-graph-hot-reloader");
        watchThread.setDaemon(true);
        watchThread.start();
        log.info("Code graph hot reloader started, watching: {}", watchRoot);
    }

    /**
     * Recursively register all sub-directories under {@code root} with the
     * watch service. New sub-directories created after start are registered
     * on the fly in {@link #watchLoop()}.
     */
    private void registerRecursive(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                register(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void register(Path dir) throws IOException {
        dir.register(watchService,
                new WatchEvent.Kind<?>[] {
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE
                },
                SensitivityWatchEventModifier.HIGH);
    }

    private void watchLoop() {
        while (running.get()) {
            WatchKey key;
            try {
                key = watchService.poll(pollIntervalMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (key == null) {
                continue;
            }
            boolean hasChanges = false;
            for (WatchEvent<?> event : key.pollEvents()) {
                Path watchedDir = (Path) key.watchable();
                Path changed = watchedDir.resolve((Path) event.context());
                if (changed.toString().endsWith(".java")) {
                    log.info("Java source file {} changed: {}", event.kind(), changed);
                    hasChanges = true;
                }
                // If a new directory was created, register it for watching
                if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE
                        && Files.isDirectory(changed)) {
                    try {
                        register(changed);
                    } catch (IOException e) {
                        log.warn("Failed to register new directory {}: {}", changed, e.getMessage());
                    }
                }
            }
            key.reset();
            if (hasChanges) {
                try {
                    index.rebuild(builder);
                    log.info("Code graph rebuilt: {} nodes", index.nodeCount());
                } catch (Exception e) {
                    log.warn("Code graph rebuild failed: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * Stops the watcher thread and closes the underlying {@link WatchService}.
     * Safe to call even if {@link #start()} never successfully started a thread.
     */
    public void stop() {
        running.set(false);
        if (watchThread != null) {
            watchThread.interrupt();
            try {
                watchThread.join(2000);
            } catch (InterruptedException e) {
                // ignore — we're shutting down
            }
        }
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException e) {
                // ignore
            }
        }
        log.info("Code graph hot reloader stopped");
    }
}
