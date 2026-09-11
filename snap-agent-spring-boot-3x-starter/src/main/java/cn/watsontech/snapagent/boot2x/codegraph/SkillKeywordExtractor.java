package cn.watsontech.snapagent.boot2x.codegraph;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Standalone extractor for skill-derived keywords, used to filter which Java
 * files enter the code graph under {@code scanMode=skills}.
 *
 * <p>This is a plain-Java utility (no Spring dependency) so it can be shared
 * between the runtime auto-configuration and the {@link CodeGraphCli}
 * integration-time builder. Both must extract the SAME keywords, otherwise the
 * CLI would build a graph that differs from what the runtime expects.</p>
 *
 * <p>Extracted keyword classes:</p>
 * <ul>
 *   <li>Java class names (CamelCase, 2+ words, starts uppercase)</li>
 *   <li>Method names (camelCase, 2+ words)</li>
 *   <li>Table names (snake_case, 3+ parts)</li>
 *   <li>Column names (snake_case, 2+ parts)</li>
 *   <li>Package names (dot-separated, 3+ segments)</li>
 * </ul>
 */
public final class SkillKeywordExtractor {

    private SkillKeywordExtractor() {
    }

    /**
     * Scan a directory recursively for {@code .md} skill files and collect all
     * extracted keywords.
     *
     * @param dir skill directory (must exist); a missing/non-directory path
     *            yields an empty set
     * @return extracted keyword set (never null)
     */
    public static Set<String> extractFromDirectory(Path dir) {
        Set<String> keywords = new HashSet<String>();
        if (dir == null || !Files.isDirectory(dir)) {
            return keywords;
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".md"))
                    .forEach(file -> {
                        try {
                            String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                            extractFromContent(content, keywords);
                        } catch (IOException ignored) {
                            // skip unreadable skill files
                        }
                    });
        } catch (IOException ignored) {
            // return whatever was collected
        }
        return keywords;
    }

    /**
     * Extract keywords from a single skill markdown document's text.
     *
     * @param content skill markdown text
     * @return extracted keyword set (never null)
     */
    public static Set<String> extractFromContent(String content) {
        Set<String> keywords = new HashSet<String>();
        extractFromContent(content, keywords);
        return keywords;
    }

    /**
     * Extract keywords from a single skill markdown document into an existing set.
     */
    public static void extractFromContent(String content, Set<String> keywords) {
        if (content == null || content.isEmpty()) {
            return;
        }
        // Pattern 1: Java class names (CamelCase, 2+ words, starts uppercase)
        Matcher classMatcher = Pattern.compile("\\b([A-Z][a-z]+(?:[A-Z][a-z]+){1,})\\b").matcher(content);
        while (classMatcher.find()) {
            String word = classMatcher.group(1);
            if (!isCommonWord(word) && word.length() >= 3) {
                keywords.add(word);
            }
        }

        // Pattern 2: Method names (camelCase, 2+ words)
        Matcher methodMatcher = Pattern.compile("\\b([a-z][a-z0-9]+(?:[A-Z][a-z0-9]+){1,})\\b").matcher(content);
        while (methodMatcher.find()) {
            String word = methodMatcher.group(1);
            if (!isCommonMethodWord(word) && word.length() >= 4) {
                keywords.add(word);
            }
        }

        // Pattern 3: Table names (snake_case, 3+ parts)
        Matcher tableMatcher = Pattern.compile("\\b([a-z][a-z0-9]+(?:_[a-z0-9]+){2,})\\b").matcher(content);
        while (tableMatcher.find()) {
            String word = tableMatcher.group(1);
            if (word.length() >= 8) {
                keywords.add(word);
            }
        }

        // Pattern 4: Column names (snake_case, 2+ parts)
        Matcher colMatcher = Pattern.compile("\\b([a-z][a-z0-9]+(?:_[a-z0-9]+)+)\\b").matcher(content);
        while (colMatcher.find()) {
            String word = colMatcher.group(1);
            if (word.length() >= 5) {
                keywords.add(word);
            }
        }

        // Pattern 5: Package names (dot-separated, 3+ parts)
        Matcher pkgMatcher = Pattern.compile("\\b([a-z][a-z0-9]+\\.[a-z][a-z0-9]+(?:\\.[a-z][a-z0-9]+)+)\\b").matcher(content);
        while (pkgMatcher.find()) {
            String word = pkgMatcher.group(1);
            if (word.length() >= 10) {
                keywords.add(word);
            }
        }
    }

    private static boolean isCommonWord(String word) {
        Set<String> commonWords = new HashSet<String>(Arrays.asList(
                "The", "This", "That", "When", "Where", "What", "How", "Why", "Which", "Who",
                "After", "Before", "During", "While", "Some", "Many", "All", "Each",
                "Use", "Used", "Using", "Check", "Make", "Made", "Take", "Need",
                "Show", "Find", "Get", "Set", "Run", "Stop", "Start", "Read", "Write",
                "Parse", "Build", "Data", "Date", "Time", "Name", "Type", "Code",
                "Id", "Key", "Value", "Item", "List", "Map", "Test", "Test"
        ));
        return commonWords.contains(word);
    }

    private static boolean isCommonMethodWord(String word) {
        Set<String> commonMethods = new HashSet<String>(Arrays.asList(
                "toString", "hashCode", "equals", "compareTo", "valueOf",
                "parseInt", "parseFloat", "parseLong", "getString", "getValue",
                "getName", "getId", "setType", "setValue", "setName", "setId",
                "isEnabled", "isEmpty", "isNull", "hasNext", "iterator",
                "contains", "indexOf", "length", "append", "insert", "delete",
                "update", "select", "query", "execute", "process"
        ));
        return commonMethods.contains(word);
    }
}
