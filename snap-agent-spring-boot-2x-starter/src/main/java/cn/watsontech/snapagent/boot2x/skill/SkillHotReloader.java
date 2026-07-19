package cn.watsontech.snapagent.boot2x.skill;

import cn.watsontech.snapagent.core.skill.SkillRegistry;
import com.sun.nio.file.SensitivityWatchEventModifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Watches the skill upload directory for changes and triggers
 * {@link SkillRegistry#refresh()} when files are created/modified/deleted.
 *
 * <p>Only {@code .md} files trigger a refresh. The watcher runs on a daemon
 * thread so it does not prevent JVM shutdown. If the watch directory does not
 * exist at {@link #start()} time, the reloader logs a warning and becomes a
 * no-op (safe to call {@link #stop()} afterwards).</p>
 *
 * <p><b>macOS note:</b> the JDK's {@code PollingWatchService} on macOS defaults
 * to a 10-second polling interval ({@code MEDIUM} sensitivity). We register with
 * {@link SensitivityWatchEventModifier#HIGH} (2&nbsp;seconds) for faster
 * detection. On Linux/Windows the modifier is silently ignored because those
 * platforms use kernel-level event mechanisms (inotify / ReadDirectoryChangesW)
 * that deliver events instantly.</p>
 */
public class SkillHotReloader {

    private static final Logger log = LoggerFactory.getLogger(SkillHotReloader.class);

    private final Path watchDir;
    private final SkillRegistry registry;
    private final long pollIntervalMs;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread watchThread;
    private WatchService watchService;

    public SkillHotReloader(Path watchDir, SkillRegistry registry, long pollIntervalMs) {
        this.watchDir = watchDir;
        this.registry = registry;
        this.pollIntervalMs = pollIntervalMs;
    }

    /**
     * Starts watching the directory. If the directory does not exist or cannot
     * be registered, a warning is logged and the reloader becomes a no-op.
     * Calling {@link #start()} more than once without {@link #stop()} is a no-op.
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            watchService = FileSystems.getDefault().newWatchService();
            if (!Files.exists(watchDir)) {
                log.warn("Skill watch dir does not exist, hot reload disabled: {}", watchDir);
                return;
            }
            watchDir.register(watchService,
                    new WatchEvent.Kind<?>[] {
                            StandardWatchEventKinds.ENTRY_CREATE,
                            StandardWatchEventKinds.ENTRY_MODIFY,
                            StandardWatchEventKinds.ENTRY_DELETE
                    },
                    SensitivityWatchEventModifier.HIGH);
        } catch (IOException e) {
            log.warn("Failed to start skill hot reloader: {}", e.getMessage());
            return;
        }
        watchThread = new Thread(this::watchLoop, "skill-hot-reloader");
        watchThread.setDaemon(true);
        watchThread.start();
        log.info("Skill hot reloader started, watching: {}", watchDir);
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
                Path changed = watchDir.resolve((Path) event.context());
                if (changed.toString().endsWith(".md")) {
                    log.info("Skill file {} changed: {}", event.kind(), changed);
                    hasChanges = true;
                }
            }
            key.reset();
            if (hasChanges) {
                try {
                    SkillRegistry.RefreshResult rr = registry.refresh();
                    log.info("Skill registry refreshed: {} total, {} available",
                            rr.getTotal(), rr.getAvailable());
                } catch (Exception e) {
                    log.warn("Skill refresh failed: {}", e.getMessage());
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
        log.info("Skill hot reloader stopped");
    }
}
