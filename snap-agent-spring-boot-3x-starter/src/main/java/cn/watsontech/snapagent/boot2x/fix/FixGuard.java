package cn.watsontech.snapagent.boot2x.fix;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates file changes before they are applied to FixContext.
 * Prevents modification of host infrastructure files.
 */
public class FixGuard {

    private final List<String> includePatterns;
    private final List<String> excludePatterns;
    private final Set<String> allowedExtensions;
    private final int maxFileCount;
    private final long maxFileSize;

    public FixGuard(SnapAgentProperties.Fix.Guard config) {
        this.includePatterns = config.getIncludePaths();
        this.excludePatterns = config.getExcludePaths();
        this.allowedExtensions = new HashSet<String>(config.getAllowedExtensions());
        this.maxFileCount = config.getMaxFileCount();
        this.maxFileSize = config.getMaxFileSize();
    }

    /**
     * Validates a file change. Throws on violation.
     */
    public void validate(String filePath, String content, String action, int currentCount) {
        // 1. Path traversal check
        if (filePath == null || filePath.contains("../") || filePath.contains("..\\")) {
            throw new SecurityException("Path traversal detected: " + filePath);
        }

        // 2. Include pattern check (must match at least one)
        boolean included = false;
        for (String pattern : includePatterns) {
            if (matchGlob(pattern, filePath)) {
                included = true;
                break;
            }
        }
        if (!included) {
            throw new RuntimeException("File not in include paths: " + filePath);
        }

        // 3. Exclude pattern check
        for (String pattern : excludePatterns) {
            if (matchGlob(pattern, filePath)) {
                throw new RuntimeException("File matches exclude pattern '" + pattern + "': " + filePath);
            }
        }

        // 4. Extension check
        String ext = getExtension(filePath);
        if (!allowedExtensions.contains(ext)) {
            throw new RuntimeException("File extension not allowed: " + ext + " for " + filePath);
        }

        // 5. File count limit
        if (currentCount >= maxFileCount) {
            throw new RuntimeException("max file count exceeded (" + maxFileCount + ")");
        }

        // 6. File size limit
        if (content != null && content.length() > maxFileSize) {
            throw new RuntimeException("File size too large: " + content.length() + " > " + maxFileSize);
        }

        // 7. Content non-empty for CREATE/UPDATE
        if (("CREATE".equals(action) || "UPDATE".equals(action))
                && (content == null || content.isEmpty())) {
            throw new RuntimeException("Content cannot be empty for " + action + " action");
        }
    }

    /**
     * Simple glob matcher: ** matches any path segments, * matches within a segment.
     */
    private boolean matchGlob(String pattern, String path) {
        String regex = pattern
                .replace(".", "\\.")
                .replace("**", "<<<GLOBSTAR>>>")
                .replace("*", "[^/]*")
                .replace("<<<GLOBSTAR>>>", ".*");
        return Pattern.matches(regex, path);
    }

    private static String getExtension(String filePath) {
        int dotIdx = filePath.lastIndexOf('.');
        if (dotIdx >= 0) {
            return filePath.substring(dotIdx).toLowerCase();
        }
        return "";
    }
}
