package com.watsontech.snapagent.core.skill;

import com.watsontech.snapagent.core.tool.ToolDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Scans a skill directory, parses {@code .md} files into {@link SkillMeta},
 * validates the tools contract against a {@link ToolDispatcher}, and caches
 * the results in memory.
 *
 * <p>The cache is stored in a volatile holder reference. {@link #refresh()}
 * re-scans the directory and atomically replaces the holder, so concurrent
 * readers never see a partially-built cache.</p>
 */
public class SkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);

    private final Path skillsDir;
    private final ToolDispatcher dispatcher;
    private final SkillLoader loader;
    private volatile Cache cache;

    /** Result of a {@link #refresh()} call. */
    public static class RefreshResult {
        private final int total;
        private final int available;
        private final int unavailable;
        private final int invalid;

        public RefreshResult(int total, int available, int unavailable, int invalid) {
            this.total = total;
            this.available = available;
            this.unavailable = unavailable;
            this.invalid = invalid;
        }

        public int getTotal() { return total; }
        public int getAvailable() { return available; }
        public int getUnavailable() { return unavailable; }
        public int getInvalid() { return invalid; }
    }

    private static class Cache {
        final List<SkillMeta> all;
        final Map<String, SkillMeta> byName;

        Cache(List<SkillMeta> all, Map<String, SkillMeta> byName) {
            this.all = Collections.unmodifiableList(all);
            this.byName = Collections.unmodifiableMap(byName);
        }
    }

    public SkillRegistry(Path skillsDir, ToolDispatcher dispatcher) {
        this(skillsDir, dispatcher, new SkillLoader());
    }

    public SkillRegistry(Path skillsDir, ToolDispatcher dispatcher, SkillLoader loader) {
        this.skillsDir = skillsDir;
        this.dispatcher = dispatcher;
        this.loader = loader;
        this.cache = new Cache(Collections.<SkillMeta>emptyList(),
                Collections.<String, SkillMeta>emptyMap());
        try {
            this.cache = scan();
        } catch (RuntimeException e) {
            log.warn("Failed to scan skills directory: {}", e.getMessage());
        }
    }

    /** Returns all loaded skill metadata (including INVALID entries). */
    public List<SkillMeta> all() {
        return cache.all;
    }

    /** Returns the skill with the given name, or {@code null} if not found. */
    public SkillMeta get(String name) {
        if (name == null) {
            return null;
        }
        return cache.byName.get(name);
    }

    /** Re-scans the directory and atomically replaces the cache. */
    public RefreshResult refresh() {
        Cache newCache;
        try {
            newCache = scan();
        } catch (RuntimeException e) {
            log.warn("Failed to refresh skills directory: {}", e.getMessage());
            return new RefreshResult(cache.all.size(), 0, 0, cache.all.size());
        }
        this.cache = newCache;

        int available = 0, unavailable = 0, invalid = 0;
        for (SkillMeta m : newCache.all) {
            switch (m.getAvailability()) {
                case AVAILABLE:
                    available++;
                    break;
                case UNAVAILABLE:
                    unavailable++;
                    break;
                case INVALID:
                    invalid++;
                    break;
                default:
                    break;
            }
        }
        return new RefreshResult(newCache.all.size(), available, unavailable, invalid);
    }

    private Cache scan() {
        if (skillsDir == null) {
            log.warn("skills-dir is null; skill registry is empty");
            return new Cache(Collections.<SkillMeta>emptyList(),
                    Collections.<String, SkillMeta>emptyMap());
        }
        if (!Files.isDirectory(skillsDir)) {
            log.warn("skills-dir not found or not a directory: {}", skillsDir);
            return new Cache(Collections.<SkillMeta>emptyList(),
                    Collections.<String, SkillMeta>emptyMap());
        }

        List<SkillMeta> metas = new ArrayList<>();
        try {
            Files.walkFileTree(skillsDir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.toString().endsWith(".md")) {
                        try {
                            String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                            SkillMeta parsed = loader.parse(content);
                            SkillMeta validated = validateContract(parsed);
                            metas.add(validated);
                        } catch (IOException e) {
                            log.warn("Failed to read skill file {}: {}", file, e.getMessage());
                        } catch (RuntimeException e) {
                            log.warn("Failed to parse skill file {}: {}", file, e.getMessage());
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("Failed to walk skill files in {}: {}", skillsDir, e.getMessage());
            return new Cache(Collections.<SkillMeta>emptyList(),
                    Collections.<String, SkillMeta>emptyMap());
        }

        if (metas.isEmpty()) {
            log.warn("no skill files found in {}", skillsDir);
        }

        Map<String, SkillMeta> byName = new HashMap<>();
        for (SkillMeta m : metas) {
            if (m.getName() != null && !m.getName().isEmpty()) {
                byName.put(m.getName(), m);
            }
        }
        return new Cache(metas, byName);
    }

    /**
     * Validates that all declared tools are registered in the dispatcher.
     * Downgrades AVAILABLE → UNAVAILABLE if any tool is missing.
     */
    private SkillMeta validateContract(SkillMeta meta) {
        if (meta.getAvailability() != SkillAvailability.AVAILABLE) {
            return meta;
        }
        if (dispatcher == null) {
            // No dispatcher → all tools considered missing
            return new SkillMeta(meta.getName(), meta.getDescription(), meta.getTools(),
                    meta.getInputs(), meta.getBody(), SkillAvailability.UNAVAILABLE,
                    "tool dispatcher not configured");
        }
        Set<String> available = dispatcher.availableToolNames();
        List<String> missing = new ArrayList<>();
        for (String tool : meta.getTools()) {
            if (!available.contains(tool)) {
                missing.add(tool);
            }
        }
        if (missing.isEmpty()) {
            return meta;
        }
        return new SkillMeta(meta.getName(), meta.getDescription(), meta.getTools(),
                meta.getInputs(), meta.getBody(), SkillAvailability.UNAVAILABLE,
                "tool(s) not available: " + missing);
    }
}
