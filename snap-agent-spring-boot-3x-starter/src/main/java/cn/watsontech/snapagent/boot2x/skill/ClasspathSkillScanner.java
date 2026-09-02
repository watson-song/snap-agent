package cn.watsontech.snapagent.boot2x.skill;

import cn.watsontech.snapagent.core.skill.SkillAvailability;
import cn.watsontech.snapagent.core.skill.SkillLoader;
import cn.watsontech.snapagent.core.skill.SkillMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Scans the classpath for built-in skill {@code .md} files and parses them into
 * {@link SkillMeta} objects with {@code source="builtin"}.
 *
 * <p>Uses Spring's {@link ResourcePatternResolver} to read resources from inside
 * the JAR (e.g. {@code classpath:/docs/skills/&#42;&#42;/&#42;.md}). Supports both
 * standalone {@code .md} files and directory skills (a subdirectory containing
 * {@code SKILL.md} - only {@code SKILL.md} is parsed, other files are
 * auxiliary).</p>
 *
 * <p>Scanning is performed once at bean creation; the classpath does not change
 * at runtime. Tool-contract validation is NOT performed here - that happens
 * later in {@link cn.watsontech.snapagent.core.skill.SkillRegistry}.</p>
 */
public class ClasspathSkillScanner {

    private static final Logger log = LoggerFactory.getLogger(ClasspathSkillScanner.class);
    private static final String SKILL_MD = "SKILL.md";

    private final SkillLoader loader;
    private final ResourcePatternResolver resolver;

    public ClasspathSkillScanner() {
        this(new SkillLoader());
    }

    public ClasspathSkillScanner(SkillLoader loader) {
        this(loader, new PathMatchingResourcePatternResolver());
    }

    public ClasspathSkillScanner(SkillLoader loader, ResourcePatternResolver resolver) {
        this.loader = loader;
        this.resolver = resolver;
    }

    /**
     * Scans the classpath directory and returns parsed builtin skills.
     *
     * <p>Resources from the SnapAgent JAR are processed first; resources from
     * the host project's classpath are processed second and will NOT override
     * SnapAgent's built-in skills with the same name. This prevents accidental
     * shadowing when the host project configures Maven {@code <resources>}
     * to include its own {@code docs/skills/} directory.</p>
     *
     * <p><b>classpath: normalization:</b> If the input uses {@code classpath:}
     * (single-root) instead of {@code classpath*:} (multi-root), it is
     * automatically normalized to {@code classpath*:}. This prevents a
     * subtle bug where the host project's {@code docs/skills/} shadows
     * the SnapAgent JAR's built-in skills — with plain {@code classpath:},
     * Spring only resolves the first classpath root, so the JAR's skills
     * are never scanned and the merge logic in this class becomes a no-op.</p>
     *
     * @param classpathDir a classpath path like {@code classpath*:/docs/skills/}
     * @return list of parsed skill metadata (source="builtin"); never {@code null}
     */
    public List<SkillMeta> scan(String classpathDir) {
        if (classpathDir == null || classpathDir.isEmpty()) {
            log.info("builtin-skills-dir not configured; no builtin skills loaded");
            return Collections.emptyList();
        }

        // Normalize classpath: → classpath*: to ensure multi-root scanning
        // (prevents host docs/skills/ from shadowing JAR built-in skills)
        String baseDir = classpathDir;
        if (baseDir.startsWith("classpath:") && !baseDir.startsWith("classpath*:")) {
            baseDir = "classpath*:" + baseDir.substring("classpath:".length());
            log.debug("Normalized builtin-skills-dir from classpath: to classpath*: for multi-root scanning");
        }

        if (!baseDir.endsWith("/")) {
            baseDir = baseDir + "/";
        }

        String pattern = baseDir + "**/*.md";
        Resource[] resources;
        try {
            resources = resolver.getResources(pattern);
        } catch (IOException e) {
            log.warn("Failed to resolve classpath resources for pattern {}: {}", pattern, e.getMessage());
            return Collections.emptyList();
        }

        if (resources.length == 0) {
            log.info("No builtin skill files found at {}", classpathDir);
            return Collections.emptyList();
        }

        // Separate resources: SnapAgent JAR resources take precedence
        List<Resource> snapAgentResources = new ArrayList<Resource>();
        List<Resource> otherResources = new ArrayList<Resource>();
        for (Resource res : resources) {
            if (isFromSnapAgentJar(res)) {
                snapAgentResources.add(res);
            } else {
                otherResources.add(res);
            }
        }

        // Process SnapAgent JAR resources first, then host project resources
        // (host resources cannot override SnapAgent built-in skill names)
        List<SkillMeta> result = new ArrayList<SkillMeta>();
        java.util.Set<String> seenNames = new java.util.HashSet<String>();

        // Pass 1: SnapAgent JAR resources
        for (SkillMeta meta : parseGroupedResources(snapAgentResources)) {
            if (meta.getName() != null) {
                seenNames.add(meta.getName());
            }
            result.add(meta.withSource("builtin"));
        }

        // Pass 2: host project resources (skip names already provided by SnapAgent)
        for (SkillMeta meta : parseGroupedResources(otherResources)) {
            if (meta.getName() != null && seenNames.contains(meta.getName())) {
                log.warn("Host project skill '{}' is shadowed by SnapAgent built-in; skipping host version",
                        meta.getName());
                continue;
            }
            if (meta.getName() != null) {
                seenNames.add(meta.getName());
            }
            result.add(meta.withSource("host"));
        }

        log.info("Loaded {} builtin skill(s) from {} ({} from SnapAgent JAR, {} from host classpath)",
                result.size(), classpathDir, snapAgentResources.size(), otherResources.size());
        return result;
    }

    /**
     * Checks if a resource originates from a SnapAgent JAR file.
     * Used to ensure host project classpath resources don't override
     * SnapAgent's built-in skills.
     */
    private boolean isFromSnapAgentJar(Resource res) {
        try {
            String url = res.getURL().toString();
            return url.contains("snap-agent-spring-boot") || url.contains("snap-agent-core");
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Groups resources by parent directory and parses them.
     * Directory skills (with SKILL.md) are parsed as a single skill;
     * standalone .md files are parsed individually.
     */
    private List<SkillMeta> parseGroupedResources(List<Resource> resources) {
        // Group resources by parent directory
        Map<String, List<Resource>> byDir = new LinkedHashMap<String, List<Resource>>();
        for (Resource res : resources) {
            String url;
            try {
                url = res.getURL().toString();
            } catch (IOException e) {
                continue;
            }
            String parentDir = parentDirectory(url);
            List<Resource> group = byDir.get(parentDir);
            if (group == null) {
                group = new ArrayList<Resource>();
                byDir.put(parentDir, group);
            }
            group.add(res);
        }

        List<SkillMeta> result = new ArrayList<SkillMeta>();
        for (Map.Entry<String, List<Resource>> entry : byDir.entrySet()) {
            List<Resource> files = entry.getValue();

            // Check if this directory has SKILL.md
            Resource skillMd = findSkillMd(files);

            if (skillMd != null) {
                // Directory skill — parse only SKILL.md, skip others
                SkillMeta meta = parseResource(skillMd);
                if (meta != null && meta.getAvailability() != SkillAvailability.INVALID) {
                    result.add(meta);
                }
            } else {
                // No SKILL.md — all .md files are standalone skills
                for (Resource res : files) {
                    SkillMeta meta = parseResource(res);
                    if (meta != null && meta.getAvailability() != SkillAvailability.INVALID) {
                        result.add(meta);
                    }
                }
            }
        }
        return result;
    }

    private SkillMeta parseResource(Resource res) {
        try (InputStream is = res.getInputStream()) {
            String content = readAll(is);
            SkillMeta meta = loader.parse(content);
            if (meta.getAvailability() == SkillAvailability.INVALID) {
                log.debug("Skipping invalid builtin skill {}: {}", res.getFilename(),
                        meta.getUnavailableReason());
                return null;
            }
            return meta;
        } catch (IOException e) {
            log.warn("Failed to read builtin skill resource {}: {}", res, e.getMessage());
            return null;
        }
    }

    private String readAll(InputStream is) throws IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int len;
        while ((len = is.read(buffer)) > 0) {
            baos.write(buffer, 0, len);
        }
        return new String(baos.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Extracts the parent directory from a resource URL string. */
    private String parentDirectory(String url) {
        int lastSlash = url.lastIndexOf('/');
        if (lastSlash < 0) {
            return "";
        }
        return url.substring(0, lastSlash);
    }

    /** Finds the SKILL.md resource in a list, if present. */
    private Resource findSkillMd(List<Resource> resources) {
        for (Resource res : resources) {
            String filename = res.getFilename();
            if (filename != null && filename.equals(SKILL_MD)) {
                return res;
            }
        }
        return null;
    }
}
