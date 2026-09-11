package cn.watsontech.snapagent.boot2x.knowledge;

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
 * Watches the knowledge root (typically {@code {upload-skills-dir}}, containing
 * the {@code domain-knowledge/} directory) for {@code .md} file changes and
 * triggers a {@link KnowledgeReloadService#reload()}.
 *
 * <p>This is the "knowledge change → auto re-ingest" hook: when an operator
 * edits a knowledge Markdown file on the server, the vector store and domain
 * index are re-built without a restart. It is OFF by default (an embedded tool
 * must not consume host resources unnecessarily); enable via
 * {@code snap-agent.knowledge.hot-reload=true}.</p>
 *
 * <p>Implementation mirrors {@code CodeGraphHotReloader}: recursive directory
 * registration (the JDK {@code WatchService} only watches one level), on-the-fly
 * registration of newly created directories, daemon thread, and no-op if the
 * watch root does not exist. A debounce window coalesces rapid successive
 * events into a single reload.</p>
 *
 * <p><b>macOS note:</b> the JDK's {@code PollingWatchService} on macOS defaults
 * to a 10-second polling interval ({@code MEDIUM} sensitivity). We register
 * with {@link SensitivityWatchEventModifier#HIGH} (2&nbsp;seconds) for faster
 * detection, same as {@code CodeGraphHotReloader}.</p>
 */
public class KnowledgeHotReloader {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeHotReloader.class);

    private final Path watchRoot;
    private final KnowledgeReloadService reloadService;
    private final long pollIntervalMs;
    private final long debounceMs;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile long lastReloadAt = 0L;
    private Thread watchThread;
    private WatchService watchService;

    public KnowledgeHotReloader(Path watchRoot, KnowledgeReloadService reloadService, long debounceMs) {
        this(watchRoot, reloadService, debounceMs, 1000L);
    }

    public KnowledgeHotReloader(Path watchRoot, KnowledgeReloadService reloadService,
                                long debounceMs, long pollIntervalMs) {
        this.watchRoot = watchRoot;
        this.reloadService = reloadService;
        this.debounceMs = debounceMs;
        this.pollIntervalMs = pollIntervalMs;
    }

    /**
     * Start watching. No-op if already running or the root does not exist.
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            watchService = FileSystems.getDefault().newWatchService();
            if (watchRoot == null || !Files.isDirectory(watchRoot)) {
                log.warn("Knowledge hot reload disabled: watch root does not exist: {}", watchRoot);
                return;
            }
            registerRecursive(watchRoot);
        } catch (IOException e) {
            log.warn("Failed to start knowledge hot reloader: {}", e.getMessage());
            return;
        }
        watchThread = new Thread(new Runnable() {
            @Override
            public void run() {
                watchLoop();
            }
        }, "knowledge-hot-reloader");
        watchThread.setDaemon(true);
        watchThread.start();
        log.info("Knowledge hot reloader started, watching: {}", watchRoot);
    }

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
            boolean relevant = false;
            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                    relevant = true;
                    continue;
                }
                Path watchedDir = (Path) key.watchable();
                Object context = event.context();
                if (context instanceof Path) {
                    Path changed = watchedDir.resolve((Path) context);
                    // If a new directory was created, register it for watching
                    if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE
                            && Files.isDirectory(changed)) {
                        try {
                            register(changed);
                        } catch (IOException e) {
                            log.warn("Failed to register new directory {}: {}", changed, e.getMessage());
                        }
                    }
                    if (changed.toString().endsWith(".md")) {
                        log.info("Knowledge file {} changed: {}", event.kind(), changed);
                        relevant = true;
                    }
                }
            }
            key.reset();
            if (relevant) {
                triggerReload();
            }
        }
    }

    private void triggerReload() {
        long now = System.currentTimeMillis();
        synchronized (this) {
            if (now - lastReloadAt < debounceMs) {
                return;
            }
            lastReloadAt = now;
        }
        try {
            KnowledgeReloadService.ReloadResult r = reloadService.reload();
            if (r != null) {
                log.info("Knowledge hot reload: {} docs, {} concepts",
                        r.getDocumentsLoaded(), r.getConceptsLoaded());
            }
        } catch (RuntimeException e) {
            log.warn("Knowledge hot reload failed: {}", e.getMessage());
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
    }
}
