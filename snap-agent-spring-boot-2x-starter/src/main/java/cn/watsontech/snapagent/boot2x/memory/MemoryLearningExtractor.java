package cn.watsontech.snapagent.boot2x.memory;

import cn.watsontech.snapagent.core.issue.IssueClosure;
import cn.watsontech.snapagent.core.llm.LlmClient;
import cn.watsontech.snapagent.core.llm.LlmEventSink;
import cn.watsontech.snapagent.core.llm.LlmRequest;
import cn.watsontech.snapagent.core.llm.Message;
import cn.watsontech.snapagent.core.llm.ToolDef;
import cn.watsontech.snapagent.core.memory.ProjectFact;
import cn.watsontech.snapagent.core.memory.UserProfile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Extracts long-term memory (UserProfile + ProjectFacts) from conversation history.
 *
 * <p>Uses LLM to analyze conversation patterns and extract:
 * <ul>
 *   <li>User preferences: language, output style, frequent services</li>
 *   <li>Project facts: tech stack, conventions, constraints discovered during diagnosis</li>
 * </ul>
 *
 * <p>Triggered during IssueClosure.close() to continuously improve the agent's
 * understanding of the user and project.</p>
 */
public class MemoryLearningExtractor {

    private static final Logger log = LoggerFactory.getLogger(MemoryLearningExtractor.class);

    private static final String SYSTEM_PROMPT =
            "You are a memory extraction system. Analyze the conversation and extract:\n"
            + "1. user_language: The language the user prefers (e.g., 'zh', 'en')\n"
            + "2. user_output_style: Preferred output style (e.g., 'concise', 'detailed', 'technical')\n"
            + "3. frequent_services: List of services/tools the user queries most\n"
            + "4. project_facts: Key-value facts about the project discovered during diagnosis\n\n"
            + "Respond with JSON:\n"
            + "{\n"
            + "  \"user_language\": \"...\",\n"
            + "  \"user_output_style\": \"...\",\n"
            + "  \"frequent_services\": [\"...\"],\n"
            + "  \"project_facts\": [{\"key\": \"...\", \"value\": \"...\"}]\n"
            + "}";

    private static final int TIMEOUT_SECONDS = 30;

    private final LlmClient llmClient;
    private final ObjectMapper mapper;

    public MemoryLearningExtractor(LlmClient llmClient) {
        this(llmClient, new ObjectMapper());
    }

    public MemoryLearningExtractor(LlmClient llmClient, ObjectMapper mapper) {
        if (llmClient == null) {
            throw new IllegalArgumentException("llmClient cannot be null");
        }
        this.llmClient = llmClient;
        this.mapper = mapper;
    }

    /**
     * Extract user profile from conversation history.
     *
     * @param messages conversation messages
     * @return extracted UserProfile, or null if extraction fails
     */
    public UserProfile extractUserProfile(String userId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }

        try {
            JsonNode result = extractWithLlm(messages);
            if (result == null) {
                return null;
            }

            String language = getTextOrNull(result, "user_language");
            String outputStyle = getTextOrNull(result, "user_output_style");
            List<String> frequentServices = extractStringList(result, "frequent_services");

            return new UserProfile(userId, language, outputStyle, frequentServices);

        } catch (Exception e) {
            log.warn("Failed to extract user profile: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Extract project facts from conversation history and issue context.
     *
     * @param messages conversation messages
     * @param issue    the issue being closed (provides diagnosis context)
     * @return list of extracted ProjectFacts, empty if extraction fails
     */
    public List<ProjectFact> extractProjectFacts(List<Message> messages, IssueClosure issue) {
        if (messages == null || messages.isEmpty()) {
            return Collections.emptyList();
        }

        try {
            JsonNode result = extractWithLlm(messages);
            if (result == null) {
                return Collections.emptyList();
            }

            List<ProjectFact> facts = new ArrayList<>();
            JsonNode factsNode = result.get("project_facts");
            if (factsNode != null && factsNode.isArray()) {
                for (JsonNode factNode : factsNode) {
                    String key = getTextOrNull(factNode, "key");
                    String value = getTextOrNull(factNode, "value");
                    if (key != null && value != null) {
                        facts.add(new ProjectFact(key, value));
                    }
                }
            }

            return facts;

        } catch (Exception e) {
            log.warn("Failed to extract project facts: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private JsonNode extractWithLlm(List<Message> messages) {
        try {
            // Build conversation text
            StringBuilder conversationText = new StringBuilder();
            for (Message msg : messages) {
                String role = msg.getRole() != null ? msg.getRole() : "unknown";
                String content = msg.getContent() != null ? msg.getContent() : "";
                // Truncate very long content
                if (content.length() > 1000) {
                    content = content.substring(0, 1000) + "... [truncated]";
                }
                conversationText.append(role).append(": ").append(content).append("\n");
            }

            // Build LLM request
            List<Message> userMessages = new ArrayList<>();
            userMessages.add(Message.user("Extract memory from this conversation:\n\n" + conversationText.toString()));

            LlmRequest request = new LlmRequest(
                    SYSTEM_PROMPT, userMessages, Collections.<ToolDef>emptyList(),
                    null, 1000, false);

            // Call LLM synchronously
            final StringBuilder accumulated = new StringBuilder();
            final CountDownLatch latch = new CountDownLatch(1);

            LlmEventSink sink = new LlmEventSink() {
                @Override
                public void onThought(String text) {
                    accumulated.append(text);
                }
                @Override
                public void onToolUse(String id, String name, Map<String, Object> input) {
                    // no-op
                }
                @Override
                public void onToolResult(String toolUseId, String result) {
                    // no-op
                }
                @Override
                public void onStop(String stopReason) {
                    latch.countDown();
                }
                @Override
                public void onError(String message) {
                    log.warn("LLM memory extraction error: {}", message);
                    latch.countDown();
                }
            };

            llmClient.stream(request, sink, "memory-extractor-" + Thread.currentThread().getId());

            boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!completed) {
                log.warn("LLM memory extraction timed out after {}s", TIMEOUT_SECONDS);
                return null;
            }

            String response = accumulated.toString().trim();
            if (response.isEmpty()) {
                return null;
            }

            // Parse JSON response
            // Try to extract JSON from response (might have markdown code blocks)
            String jsonStr = extractJson(response);
            if (jsonStr == null) {
                log.warn("Failed to extract JSON from LLM response");
                return null;
            }

            return mapper.readTree(jsonStr);

        } catch (Exception e) {
            log.warn("LLM memory extraction failed: {}", e.getMessage());
            return null;
        }
    }

    private String extractJson(String text) {
        // Try to find JSON object in the text
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return null;
    }

    private String getTextOrNull(JsonNode node, String field) {
        JsonNode fieldNode = node.get(field);
        if (fieldNode != null && fieldNode.isTextual()) {
            String text = fieldNode.asText();
            return text.isEmpty() ? null : text;
        }
        return null;
    }

    private List<String> extractStringList(JsonNode node, String field) {
        JsonNode fieldNode = node.get(field);
        if (fieldNode != null && fieldNode.isArray()) {
            List<String> result = new ArrayList<>();
            for (JsonNode item : fieldNode) {
                if (item.isTextual()) {
                    String text = item.asText();
                    if (!text.isEmpty()) {
                        result.add(text);
                    }
                }
            }
            return result;
        }
        return Collections.emptyList();
    }
}
