package cn.watsontech.snapagent.boot2x.memory;

import cn.watsontech.snapagent.core.issue.IssueClosure;
import cn.watsontech.snapagent.core.issue.IssueStatus;
import cn.watsontech.snapagent.core.llm.LlmClient;
import cn.watsontech.snapagent.core.llm.LlmEventSink;
import cn.watsontech.snapagent.core.llm.LlmRequest;
import cn.watsontech.snapagent.core.llm.Message;
import cn.watsontech.snapagent.core.memory.ProjectFact;
import cn.watsontech.snapagent.core.memory.UserProfile;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link MemoryLearningExtractor}.
 */
class MemoryLearningExtractorTest {

    @Test
    void constructor_nullClient_throws() {
        assertThatThrownBy(() -> new MemoryLearningExtractor(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("llmClient");
    }

    @Test
    void extractUserProfile_emptyMessages_returnsNull() {
        LlmClient mockClient = createMockClient("{}");
        MemoryLearningExtractor extractor = new MemoryLearningExtractor(mockClient);

        UserProfile profile = extractor.extractUserProfile("user-1", Collections.<Message>emptyList());
        assertThat(profile).isNull();
    }

    @Test
    void extractUserProfile_nullMessages_returnsNull() {
        LlmClient mockClient = createMockClient("{}");
        MemoryLearningExtractor extractor = new MemoryLearningExtractor(mockClient);

        UserProfile profile = extractor.extractUserProfile("user-1", null);
        assertThat(profile).isNull();
    }

    @Test
    void extractUserProfile_validResponse_returnsProfile() {
        String jsonResponse = "{\n"
                + "  \"user_language\": \"zh\",\n"
                + "  \"user_output_style\": \"concise\",\n"
                + "  \"frequent_services\": [\"mysql_query\", \"code_read\"]\n"
                + "}";

        LlmClient mockClient = createMockClient(jsonResponse);
        MemoryLearningExtractor extractor = new MemoryLearningExtractor(mockClient);

        List<Message> messages = Arrays.asList(
                Message.user("帮我查一下这个SKU的补货策略"),
                Message.assistant("好的，让我查询一下...", null)
        );

        UserProfile profile = extractor.extractUserProfile("user-1", messages);

        assertThat(profile).isNotNull();
        assertThat(profile.getUserId()).isEqualTo("user-1");
        assertThat(profile.getLanguage()).isEqualTo("zh");
        assertThat(profile.getOutputStyle()).isEqualTo("concise");
        assertThat(profile.getFrequentServices()).containsExactly("mysql_query", "code_read");
    }

    @Test
    void extractUserProfile_partialResponse_returnsPartialProfile() {
        String jsonResponse = "{\n"
                + "  \"user_language\": \"en\"\n"
                + "}";

        LlmClient mockClient = createMockClient(jsonResponse);
        MemoryLearningExtractor extractor = new MemoryLearningExtractor(mockClient);

        List<Message> messages = Collections.singletonList(Message.user("Help me debug this"));

        UserProfile profile = extractor.extractUserProfile("user-2", messages);

        assertThat(profile).isNotNull();
        assertThat(profile.getLanguage()).isEqualTo("en");
        assertThat(profile.getOutputStyle()).isNull();
        assertThat(profile.getFrequentServices()).isEmpty();
    }

    @Test
    void extractUserProfile_llmReturnsInvalidJson_returnsNull() {
        LlmClient mockClient = createMockClient("This is not JSON");
        MemoryLearningExtractor extractor = new MemoryLearningExtractor(mockClient);

        List<Message> messages = Collections.singletonList(Message.user("test"));

        UserProfile profile = extractor.extractUserProfile("user-3", messages);
        assertThat(profile).isNull();
    }

    @Test
    void extractUserProfile_llmThrows_returnsNull() {
        LlmClient mockClient = (req, sink, taskId) -> {
            throw new RuntimeException("LLM unavailable");
        };
        MemoryLearningExtractor extractor = new MemoryLearningExtractor(mockClient);

        List<Message> messages = Collections.singletonList(Message.user("test"));

        UserProfile profile = extractor.extractUserProfile("user-4", messages);
        assertThat(profile).isNull();
    }

    @Test
    void extractProjectFacts_emptyMessages_returnsEmptyList() {
        LlmClient mockClient = createMockClient("{}");
        MemoryLearningExtractor extractor = new MemoryLearningExtractor(mockClient);

        IssueClosure issue = createTestIssue("issue-1");

        List<ProjectFact> facts = extractor.extractProjectFacts(Collections.<Message>emptyList(), issue);
        assertThat(facts).isEmpty();
    }

    @Test
    void extractProjectFacts_validResponse_returnsFacts() {
        String jsonResponse = "{\n"
                + "  \"project_facts\": [\n"
                + "    {\"key\": \"tech-stack\", \"value\": \"Spring Boot 2.5 + MyBatis\"},\n"
                + "    {\"key\": \"database\", \"value\": \"MySQL 8.0\"},\n"
                + "    {\"key\": \"known-constraint\", \"value\": \"安全库存表有15分钟延迟\"}\n"
                + "  ]\n"
                + "}";

        LlmClient mockClient = createMockClient(jsonResponse);
        MemoryLearningExtractor extractor = new MemoryLearningExtractor(mockClient);

        List<Message> messages = Arrays.asList(
                Message.user("为什么这个SKU没有生成补货策略？"),
                Message.assistant("让我查一下...", null),
                Message.user("原来是安全库存还没更新"),
                Message.assistant("是的，安全库存表有15分钟延迟", null)
        );

        IssueClosure issue = createTestIssue("issue-2");

        List<ProjectFact> facts = extractor.extractProjectFacts(messages, issue);

        assertThat(facts).hasSize(3);
        assertThat(facts.get(0).getKey()).isEqualTo("tech-stack");
        assertThat(facts.get(0).getValue()).isEqualTo("Spring Boot 2.5 + MyBatis");
        assertThat(facts.get(2).getKey()).isEqualTo("known-constraint");
        assertThat(facts.get(2).getValue()).isEqualTo("安全库存表有15分钟延迟");
    }

    @Test
    void extractProjectFacts_noProjectFactsField_returnsEmptyList() {
        String jsonResponse = "{\n"
                + "  \"user_language\": \"zh\"\n"
                + "}";

        LlmClient mockClient = createMockClient(jsonResponse);
        MemoryLearningExtractor extractor = new MemoryLearningExtractor(mockClient);

        List<Message> messages = Collections.singletonList(Message.user("test"));
        IssueClosure issue = createTestIssue("issue-3");

        List<ProjectFact> facts = extractor.extractProjectFacts(messages, issue);
        assertThat(facts).isEmpty();
    }

    @Test
    void extractProjectFacts_malformedFactEntries_skipsInvalid() {
        String jsonResponse = "{\n"
                + "  \"project_facts\": [\n"
                + "    {\"key\": \"valid-key\", \"value\": \"valid-value\"},\n"
                + "    {\"key\": \"missing-value\"},\n"
                + "    {\"value\": \"missing-key\"},\n"
                + "    \"not-an-object\"\n"
                + "  ]\n"
                + "}";

        LlmClient mockClient = createMockClient(jsonResponse);
        MemoryLearningExtractor extractor = new MemoryLearningExtractor(mockClient);

        List<Message> messages = Collections.singletonList(Message.user("test"));
        IssueClosure issue = createTestIssue("issue-4");

        List<ProjectFact> facts = extractor.extractProjectFacts(messages, issue);

        // Only the first entry is valid (has both key and value)
        assertThat(facts).hasSize(1);
        assertThat(facts.get(0).getKey()).isEqualTo("valid-key");
        assertThat(facts.get(0).getValue()).isEqualTo("valid-value");
    }

    @Test
    void extractWithLlm_jsonInMarkdownCodeBlock_extractsCorrectly() {
        // LLM sometimes wraps JSON in markdown code blocks
        String response = "Here's the extracted memory:\n"
                + "```json\n"
                + "{\n"
                + "  \"user_language\": \"zh\",\n"
                + "  \"user_output_style\": \"detailed\"\n"
                + "}\n"
                + "```";

        LlmClient mockClient = createMockClient(response);
        MemoryLearningExtractor extractor = new MemoryLearningExtractor(mockClient);

        List<Message> messages = Collections.singletonList(Message.user("测试"));

        UserProfile profile = extractor.extractUserProfile("user-5", messages);

        assertThat(profile).isNotNull();
        assertThat(profile.getLanguage()).isEqualTo("zh");
        assertThat(profile.getOutputStyle()).isEqualTo("detailed");
    }

    // ---- Helper methods ----

    private LlmClient createMockClient(String response) {
        return new LlmClient() {
            @Override
            public void stream(LlmRequest req, LlmEventSink sink, String taskId) {
                sink.onThought(response);
                sink.onStop("end_turn");
            }
        };
    }

    private IssueClosure createTestIssue(String issueId) {
        return new IssueClosure(
                issueId,
                null,  // externalIssueId
                null,  // externalIssueSource
                "task-1",
                "conv-1",
                "user-1",
                "测试问题",
                "测试根因",
                null,  // solution
                null,  // selectedSolution
                IssueStatus.CLOSED,
                null,  // fixCommitId
                null,  // fixPrUrl
                null,  // fixPrNumber
                null,  // verificationResult
                null,  // knowledgeEntryId
                System.currentTimeMillis(),
                System.currentTimeMillis()
        );
    }
}
