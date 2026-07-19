package cn.watsontech.snapagent.boot2x.patrol;

import cn.watsontech.snapagent.core.agent.TranscriptEvent;
import cn.watsontech.snapagent.core.patrol.BugfixSuggester;
import cn.watsontech.snapagent.core.patrol.BugfixSuggestion;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Template-based BugfixSuggester that scans transcript for code_read and git_log
 * tool calls to extract affected files, commit references, and root cause.
 *
 * <p>Confidence levels:
 * <ul>
 *   <li>HIGH — both code_read and git_log were used</li>
 *   <li>MEDIUM — only one of code_read/git_log was used</li>
 *   <li>LOW — neither was used</li>
 * </ul></p>
 */
public class TemplateBugfixSuggester implements BugfixSuggester {

    private static final Pattern FILE_PATH_PATTERN = Pattern.compile(
            "\"file_path\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern COMMIT_HASH_PATTERN = Pattern.compile(
            "\\b([a-f0-9]{6,40})\\b");

    @Override
    public BugfixSuggestion suggest(String taskId, List<TranscriptEvent> transcript) {
        if (transcript == null || transcript.isEmpty()) {
            BugfixSuggestion empty = new BugfixSuggestion();
            empty.setTaskId(taskId);
            empty.setConfidence(BugfixSuggestion.CONFIDENCE_LOW);
            empty.setSuggestion("No diagnostic data available to generate a bugfix suggestion.");
            return empty;
        }

        Set<String> affectedFiles = new HashSet<>();
        Set<String> commitRefs = new HashSet<>();
        String rootCause = null;

        // Build map from tool call id to tool name for matching results
        Map<String, String> toolCallIdToName = new LinkedHashMap<>();

        for (TranscriptEvent event : transcript) {
            String type = event.getType();

            if (TranscriptEvent.TYPE_TOOL_CALL.equals(type)) {
                Map<String, Object> data = event.getData();
                String toolName = (String) data.get("name");
                String callId = (String) data.get("id");
                if (callId != null && toolName != null) {
                    toolCallIdToName.put(callId, toolName);
                }
                if ("code_read".equals(toolName) || "git_log".equals(toolName)) {
                    Object argsObj = data.get("args");
                    extractFilePaths(argsObj, affectedFiles);
                }
            } else if (TranscriptEvent.TYPE_TOOL_RESULT.equals(type)) {
                Map<String, Object> data = event.getData();
                String resultId = (String) data.get("id");
                String toolName = resultId != null ? toolCallIdToName.get(resultId) : null;
                if ("git_log".equals(toolName)) {
                    Object contentObj = data.get("content");
                    String content = contentObj != null ? contentObj.toString() : "";
                    extractCommitHashes(content, commitRefs);
                }
            } else if (TranscriptEvent.TYPE_DONE.equals(type)) {
                Object reportObj = event.getData().get("report");
                if (reportObj != null) {
                    rootCause = reportObj.toString();
                }
            }
        }

        String confidence;
        if (!affectedFiles.isEmpty() && !commitRefs.isEmpty()) {
            confidence = BugfixSuggestion.CONFIDENCE_HIGH;
        } else if (!affectedFiles.isEmpty() || !commitRefs.isEmpty()) {
            confidence = BugfixSuggestion.CONFIDENCE_MEDIUM;
        } else {
            confidence = BugfixSuggestion.CONFIDENCE_LOW;
        }

        String suggestion = buildSuggestion(rootCause, affectedFiles, commitRefs, confidence);

        return new BugfixSuggestion(taskId, rootCause,
                new ArrayList<>(affectedFiles), suggestion, confidence,
                new ArrayList<>(commitRefs));
    }

    @SuppressWarnings("unchecked")
    private void extractFilePaths(Object argsObj, Set<String> files) {
        if (argsObj == null) return;
        // If args is a Map, extract file_path directly (the common case from
        // TranscriptEvent.toolCall which stores args as Map<String, Object>)
        if (argsObj instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) argsObj;
            Object filePath = map.get("file_path");
            if (filePath != null) {
                files.add(String.valueOf(filePath));
            }
        }
        // Also try regex on toString() for JSON-string args
        Matcher m = FILE_PATH_PATTERN.matcher(argsObj.toString());
        while (m.find()) {
            files.add(m.group(1));
        }
    }

    private void extractCommitHashes(String text, Set<String> commits) {
        if (text == null) return;
        Matcher m = COMMIT_HASH_PATTERN.matcher(text);
        while (m.find()) {
            commits.add(m.group(1));
        }
    }

    private String buildSuggestion(String rootCause, Set<String> files,
                                   Set<String> commits, String confidence) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Bugfix Suggestion\n\n");
        sb.append("### Root Cause\n");
        sb.append(rootCause != null ? rootCause : "Not identified").append("\n\n");

        sb.append("### Affected Files\n");
        if (files.isEmpty()) {
            sb.append("No files identified in diagnostic.\n");
        } else {
            for (String file : files) {
                sb.append("- ").append(file).append("\n");
            }
        }
        sb.append("\n");

        sb.append("### Suggested Fix\n");
        sb.append("Based on the root cause analysis, review the following files:\n");
        if (files.isEmpty()) {
            sb.append("- No specific files identified; review the diagnostic transcript.\n");
        } else {
            for (String file : files) {
                sb.append("- ").append(file)
                        .append(": Check the logic identified in the diagnostic\n");
            }
        }
        sb.append("\n");

        sb.append("### Related Commits\n");
        if (commits.isEmpty()) {
            sb.append("No commits identified.\n");
        } else {
            for (String commit : commits) {
                sb.append("- ").append(commit).append("\n");
            }
        }
        sb.append("\n### Confidence: ").append(confidence).append("\n");

        return sb.toString();
    }
}
