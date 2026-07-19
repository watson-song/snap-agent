package cn.watsontech.snapagent.core.patrol;

import cn.watsontech.snapagent.core.agent.TranscriptEvent;

import java.util.List;

/**
 * SPI for generating bugfix suggestions from patrol task transcripts.
 */
public interface BugfixSuggester {

    /**
     * Analyzes a patrol task transcript and produces a bugfix suggestion.
     *
     * @param taskId     the ID of the patrol task
     * @param transcript the transcript events from the task execution
     * @return a bugfix suggestion, or null if no suggestion can be made
     */
    BugfixSuggestion suggest(String taskId, List<TranscriptEvent> transcript);
}
