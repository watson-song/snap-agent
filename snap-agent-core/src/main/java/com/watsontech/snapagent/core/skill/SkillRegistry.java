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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Scans built-in (classpath) and upload (filesystem) skill sources, parses
 * {@code .md} files into {@link SkillMeta}, validates the tools contract
 * against a {@link ToolDispatcher}, and caches the merged results in memory.
 *
 * <p><b>Two-tier model:</b> built-in skills are passed in as a pre-parsed list
 * (typically from {@code ClasspathSkillScanner} in the starter module). Upload
 * skills are scanned from the filesystem {@code uploadDir}. When a custom skill
 * has the same {@code name} as a builtin, the custom one wins (shadows the
 * builtin); {@link SkillMeta#isOverridesBuiltin()} is set to {@code true}.</p>
 *
 * <p><b>Directory skills:</b> a subdirectory containing {@code SKILL.md} is
 * treated as a single skill — only {@code SKILL.md} is parsed, other files are
 * auxiliary. Subdirectories without {@code SKILL.md} are organizational and
 * recursed into.</p>
 *
 * <p>The cache is stored in a volatile holder reference. {@link #refresh()}
 * re-scans the upload directory and atomically replaces the holder, so
 * concurrent readers never see a partially-built cache.</p>
 */
public class SkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);

    private static final String SKILL_MD = "SKILL.md";

    private final Path uploadDir;
    private final List<SkillMeta> builtinMetas;
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
        final Map<String, Path> customSkillPaths;

        Cache(List<SkillMeta> all, Map<String, SkillMeta> byName,
              Map<String, Path> customSkillPaths) {
            this.all = Collections.unmodifiableList(all);
            this.byName = Collections.unmodifiableMap(byName);
            this.customSkillPaths = Collections.unmodifiableMap(customSkillPaths);
        }
    }

    public SkillRegistry(Path uploadDir, ToolDispatcher dispatcher) {
        this(uploadDir, Collections.<SkillMeta>emptyList(), dispatcher);
    }

    public SkillRegistry(Path uploadDir, List<SkillMeta> builtinSkills,
                         ToolDispatcher dispatcher) {
        this(uploadDir, builtinSkills, dispatcher, new SkillLoader());
    }

    public SkillRegistry(Path uploadDir, List<SkillMeta> builtinSkills,
                         ToolDispatcher dispatcher, SkillLoader loader) {
        this.uploadDir = uploadDir != null
                ? uploadDir.toAbsolutePath().normalize() : null;
        this.builtinMetas = builtinSkills == null
                ? Collections.<SkillMeta>emptyList()
                : new ArrayList<SkillMeta>(builtinSkills);
        this.dispatcher = dispatcher;
        this.loader = loader;
        this.cache = new Cache(Collections.<SkillMeta>emptyList(),
                Collections.<String, SkillMeta>emptyMap(),
                Collections.<String, Path>emptyMap());
        try {
            this.cache = scan();
        } catch (RuntimeException e) {
            log.warn("Failed to scan skills directory: {}", e.getMessage());
        }
    }

    /** Returns all merged skill metadata (builtin + custom, custom wins on name conflict). */
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

    /** Returns true if a builtin skill with the given name exists. */
    public boolean isBuiltin(String name) {
        if (name == null) {
            return false;
        }
        for (SkillMeta meta : builtinMetas) {
            if (name.equals(meta.getName())) {
                return true;
            }
        }
        return false;
    }

    /** Returns the filesystem path (file or directory) for a custom skill, or {@code null}. */
    public Path getCustomSkillPath(String name) {
        if (name == null) {
            return null;
        }
        return cache.customSkillPaths.get(name);
    }

    /** Returns the upload directory path (for controller upload/delete operations). */
    public Path getUploadDir() {
        return uploadDir;
    }

    /** Re-scans the upload directory and atomically replaces the cache. */
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
        // 1. Validate builtin skills and index by name
        Map<String, SkillMeta> builtinByName = new LinkedHashMap<String, SkillMeta>();
        for (SkillMeta meta : builtinMetas) {
            if (meta.getName() == null || meta.getAvailability() == SkillAvailability.INVALID) {
                log.debug("Skipping invalid builtin skill: {}", meta.getUnavailableReason());
                continue;
            }
            SkillMeta validated = validateContract(meta).withSource("builtin");
            builtinByName.put(validated.getName(), validated);
        }

        // 2. Scan upload directory (filesystem)
        Map<String, SkillMeta> customByName = new LinkedHashMap<String, SkillMeta>();
        Map<String, Path> customPaths = new HashMap<String, Path>();

        if (uploadDir == null) {
            log.warn("upload-skills-dir is null; only builtin skills will be loaded");
        } else if (!Files.isDirectory(uploadDir)) {
            log.warn("upload-skills-dir not found or not a directory: {}", uploadDir);
        } else {
            try {
                Files.walkFileTree(uploadDir, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        // Root directory is always organizational
                        if (dir.equals(uploadDir)) {
                            return FileVisitResult.CONTINUE;
                        }
                        // Subdirectory with SKILL.md → directory skill
                        Path skillMd = dir.resolve(SKILL_MD);
                        if (Files.exists(skillMd)) {
                            try {
                                String content = new String(Files.readAllBytes(skillMd),
                                        StandardCharsets.UTF_8);
                                SkillMeta parsed = loader.parse(content);
                                if (parsed.getAvailability() == SkillAvailability.INVALID) {
                                    log.debug("Skipping invalid directory skill {}: {}",
                                            skillMd, parsed.getUnavailableReason());
                                    return FileVisitResult.SKIP_SUBTREE;
                                }
                                SkillMeta validated = validateContract(parsed).withSource("custom");
                                if (customByName.containsKey(validated.getName())) {
                                    log.warn("Duplicate custom skill name '{}': overriding previous",
                                            validated.getName());
                                }
                                customByName.put(validated.getName(), validated);
                                customPaths.put(validated.getName(), dir);
                            } catch (IOException e) {
                                log.warn("Failed to read skill file {}: {}",
                                        skillMd, e.getMessage());
                            } catch (RuntimeException e) {
                                log.warn("Failed to parse skill file {}: {}",
                                        skillMd, e.getMessage());
                            }
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        // No SKILL.md → organizational directory, recurse
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        String fileName = file.getFileName().toString();
                        if (fileName.endsWith(".md")) {
                            try {
                                String content = new String(Files.readAllBytes(file),
                                        StandardCharsets.UTF_8);
                                SkillMeta parsed = loader.parse(content);
                                if (parsed.getAvailability() == SkillAvailability.INVALID) {
                                    log.debug("Skipping invalid skill file {}: {}",
                                            file, parsed.getUnavailableReason());
                                    return FileVisitResult.CONTINUE;
                                }
                                SkillMeta validated = validateContract(parsed).withSource("custom");
                                if (customByName.containsKey(validated.getName())) {
                                    log.warn("Duplicate custom skill name '{}': overriding previous",
                                            validated.getName());
                                }
                                customByName.put(validated.getName(), validated);
                                customPaths.put(validated.getName(), file);
                            } catch (IOException e) {
                                log.warn("Failed to read skill file {}: {}",
                                        file, e.getMessage());
                            } catch (RuntimeException e) {
                                log.warn("Failed to parse skill file {}: {}",
                                        file, e.getMessage());
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                log.warn("Failed to walk skill files in {}: {}", uploadDir, e.getMessage());
            }
        }

        if (builtinByName.isEmpty() && customByName.isEmpty()) {
            log.warn("no skill files found in builtin or upload directories");
        }

        // 3. Merge: custom overrides builtin by name
        List<SkillMeta> merged = new ArrayList<SkillMeta>();
        Map<String, SkillMeta> byName = new LinkedHashMap<String, SkillMeta>();

        for (SkillMeta meta : builtinByName.values()) {
            if (!customByName.containsKey(meta.getName())) {
                merged.add(meta);
                byName.put(meta.getName(), meta);
            }
        }

        for (SkillMeta meta : customByName.values()) {
            SkillMeta result = meta;
            if (builtinByName.containsKey(meta.getName())) {
                result = meta.withOverridesBuiltin(true);
            }
            merged.add(result);
            byName.put(result.getName(), result);
        }

        return new Cache(merged, byName, customPaths);
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
            return new SkillMeta(meta.getName(), meta.getDescription(), meta.getTools(),
                    meta.getInputs(), meta.getBody(), SkillAvailability.UNAVAILABLE,
                    "tool dispatcher not configured", meta.getSource(),
                    meta.isOverridesBuiltin());
        }
        Set<String> available = dispatcher.availableToolNames();
        List<String> missing = new ArrayList<String>();
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
                "tool(s) not available: " + missing, meta.getSource(),
                meta.isOverridesBuiltin());
    }
}
