package cn.watsontech.snapagent.boot2x.knowledge;

import cn.watsontech.snapagent.core.knowledge.KnowledgeFragment;
import cn.watsontech.snapagent.core.knowledge.KnowledgeSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * {@link KnowledgeSource} that loads Markdown files from a directory and splits
 * each file into fragments by {@code ## } (level-2) headings.
 *
 * <p>Supports two location modes:</p>
 * <ul>
 *   <li><b>classpath:</b> — resources packaged inside the JAR (e.g.
 *       {@code classpath:/docs/knowledge/}). Uses Spring's
 *       {@link PathMatchingResourcePatternResolver} to scan the classpath.</li>
 *   <li><b>filesystem:</b> — absolute or relative directory paths. Scans the
 *       file system directly.</li>
 * </ul>
 *
 * <p><b>Splitting rules:</b></p>
 * <ul>
 *   <li>A line starting with {@code # } is the file-level title and is stored
 *       as the {@code category} metadata of every fragment in that file.</li>
 *   <li>Content before the first {@code ## } heading (file title + intro) is
 *       emitted as a single "overview" fragment.</li>
 *   <li>Each {@code ## } heading starts a new fragment: the heading text is the
 *       fragment title, and the body until the next {@code ## } (or EOF) is the
 *       content.</li>
 *   <li>A file with no {@code ## } headings produces one fragment containing
 *       the entire file content (title = file-level heading or filename).</li>
 * </ul>
 */
public class MarkdownKnowledgeSource implements KnowledgeSource {

    private static final Logger log = LoggerFactory.getLogger(MarkdownKnowledgeSource.class);

    private final String dir;
    private final ResourcePatternResolver resourceResolver;

    public MarkdownKnowledgeSource(String dir) {
        this(dir, new PathMatchingResourcePatternResolver());
    }

    /**
     * Constructor with a custom resource resolver (for testing).
     *
     * @param dir             directory path (classpath: or filesystem)
     * @param resourceResolver Spring resource pattern resolver
     */
    public MarkdownKnowledgeSource(String dir, ResourcePatternResolver resourceResolver) {
        this.dir = dir;
        this.resourceResolver = resourceResolver != null ? resourceResolver
                : new PathMatchingResourcePatternResolver();
    }

    @Override
    public List<KnowledgeFragment> load() {
        List<KnowledgeFragment> all = new ArrayList<KnowledgeFragment>();
        if (dir == null || dir.isEmpty()) {
            return all;
        }

        List<ResourceEntry> resources = resolveResources();
        for (ResourceEntry entry : resources) {
            String content = entry.content;
            String fileName = entry.fileName;
            if (content == null || content.isEmpty()) {
                continue;
            }
            all.addAll(splitIntoFragments(content, fileName));
        }
        log.info("MarkdownKnowledgeSource loaded {} fragments from {} file(s) in {}",
                all.size(), resources.size(), dir);
        return all;
    }

    @Override
    public void reload() {
        // State-less: load() re-reads on each call. Nothing to reset.
    }

    @Override
    public String type() {
        return "markdown";
    }

    // ---- internal ----

    private List<ResourceEntry> resolveResources() {
        List<ResourceEntry> entries = new ArrayList<ResourceEntry>();
        if (dir.startsWith("classpath:")) {
            String pattern = dir.endsWith("/") ? dir + "**/*.md" : dir + "/**/*.md";
            try {
                Resource[] resources = resourceResolver.getResources(pattern);
                for (Resource res : resources) {
                    String content = readResource(res);
                    entries.add(new ResourceEntry(content, res.getFilename()));
                }
            } catch (Exception e) {
                log.warn("Failed to scan classpath knowledge dir {}: {}", dir, e.getMessage());
            }
        } else {
            Path root = Paths.get(dir);
            if (!Files.isDirectory(root)) {
                log.warn("Knowledge dir does not exist or is not a directory: {}", dir);
                return entries;
            }
            try (Stream<Path> stream = Files.walk(root)) {
                List<Path> mdFiles = new ArrayList<Path>();
                stream.filter(Files::isRegularFile)
                        .filter(p -> p.toString().toLowerCase().endsWith(".md"))
                        .forEach(mdFiles::add);
                for (Path p : mdFiles) {
                    String content = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                    entries.add(new ResourceEntry(content, p.getFileName().toString()));
                }
            } catch (Exception e) {
                log.warn("Failed to scan filesystem knowledge dir {}: {}", dir, e.getMessage());
            }
        }
        return entries;
    }

    private String readResource(Resource res) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                res.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        } catch (Exception e) {
            log.warn("Failed to read knowledge resource {}: {}", res.getFilename(), e.getMessage());
        }
        return sb.toString();
    }

    /**
     * Splits a single Markdown file's content into knowledge fragments.
     */
    List<KnowledgeFragment> splitIntoFragments(String content, String fileName) {
        List<KnowledgeFragment> fragments = new ArrayList<KnowledgeFragment>();
        String category = "";

        // Extract file-level title (# heading)
        String[] lines = content.split("\n", -1);
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("# ") && !trimmed.startsWith("## ")) {
                category = trimmed.substring(2).trim();
                break;
            }
        }

        // Split by ## headings
        List<Section> sections = extractSections(content);
        if (sections.isEmpty()) {
            // No ## headings — entire file is one fragment
            Map<String, String> meta = new LinkedHashMap<String, String>();
            if (!category.isEmpty()) {
                meta.put("category", category);
            }
            String title = !category.isEmpty() ? category : fileName;
            fragments.add(new KnowledgeFragment(title, content.trim(),
                    fileName + ":overview", meta));
            return fragments;
        }

        // If there's content before the first ## heading, emit as "overview"
        int firstH2 = content.indexOf("\n## ");
        if (firstH2 < 0) {
            firstH2 = content.indexOf("## ");
        }
        if (firstH2 > 0) {
            String overview = content.substring(0, firstH2).trim();
            if (!overview.isEmpty()) {
                Map<String, String> meta = new LinkedHashMap<String, String>();
                if (!category.isEmpty()) {
                    meta.put("category", category);
                }
                String title = !category.isEmpty() ? category + " — 概述" : fileName + " — 概述";
                fragments.add(new KnowledgeFragment(title, overview,
                        fileName + ":overview", meta));
            }
        }

        for (int i = 0; i < sections.size(); i++) {
            Section s = sections.get(i);
            Map<String, String> meta = new LinkedHashMap<String, String>();
            if (!category.isEmpty()) {
                meta.put("category", category);
            }
            fragments.add(new KnowledgeFragment(s.title, s.body.trim(),
                    fileName + ":section-" + (i + 1), meta));
        }

        return fragments;
    }

    /**
     * Extracts sections delimited by {@code ## } headings.
     */
    private List<Section> extractSections(String content) {
        List<Section> sections = new ArrayList<Section>();
        String[] lines = content.split("\n", -1);
        int currentSectionStart = -1;
        String currentTitle = null;
        StringBuilder currentBody = new StringBuilder();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            if (trimmed.startsWith("## ")) {
                // Save previous section
                if (currentTitle != null) {
                    sections.add(new Section(currentTitle, currentBody.toString()));
                }
                currentTitle = trimmed.substring(3).trim();
                currentBody = new StringBuilder();
                currentSectionStart = i;
            } else if (currentTitle != null) {
                currentBody.append(line).append("\n");
            }
        }
        // Save last section
        if (currentTitle != null) {
            sections.add(new Section(currentTitle, currentBody.toString()));
        }
        return sections;
    }

    private static class Section {
        final String title;
        final String body;

        Section(String title, String body) {
            this.title = title;
            this.body = body;
        }
    }

    private static class ResourceEntry {
        final String content;
        final String fileName;

        ResourceEntry(String content, String fileName) {
            this.content = content;
            this.fileName = fileName;
        }
    }
}
