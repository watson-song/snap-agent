package cn.watsontech.snapagent.boot2x.knowledge;

import cn.watsontech.snapagent.core.embedding.EmbeddingModel;
import cn.watsontech.snapagent.core.issue.IssueClosure;
import cn.watsontech.snapagent.core.issue.IssueStatus;
import cn.watsontech.snapagent.core.issue.SolutionOption;
import cn.watsontech.snapagent.core.issue.SolutionSuggestion;
import cn.watsontech.snapagent.core.vectorstore.Document;
import cn.watsontech.snapagent.core.vectorstore.InMemoryVectorStore;
import cn.watsontech.snapagent.core.vectorstore.VectorStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("KnowledgeSedimentationService — extract + sediment")
class KnowledgeSedimentationServiceTest {

    private InMemoryVectorStore store;
    private EmbeddingModel embeddingModel;
    private KnowledgeSedimentationService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryVectorStore();
        embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{1.0f, 0.0f});
        service = new KnowledgeSedimentationService(store, embeddingModel);
    }

    private IssueClosure createIssue(String issueId, String userQuery, String rootCause,
                                      String selectedSolution) {
        return new IssueClosure(issueId, null, null, null, null, "u1",
                userQuery, rootCause, null, selectedSolution,
                IssueStatus.CLOSED, null, null, null, null, null,
                System.currentTimeMillis(), System.currentTimeMillis());
    }

    // UC-20: extract 含 ## 问题/根因/解决方案
    @Test
    @DisplayName("extract → Document 含 ## 问题 + ## 根因 + ## 解决方案")
    void shouldExtractDocumentWithThreeSections() {
        IssueClosure issue = createIssue("issue-001",
                "为什么订单超时?", "连接池打满", "扩容连接池");

        Document doc = service.extract(issue);

        assertThat(doc).isNotNull();
        assertThat(doc.getContent()).contains("## 问题");
        assertThat(doc.getContent()).contains("## 根因");
        assertThat(doc.getContent()).contains("## 解决方案");
        assertThat((String) doc.getMetadata("source")).isEqualTo("sedimentation:issue-001");
        assertThat((String) doc.getMetadata("category")).isEqualTo("经验沉淀");
    }

    // UC-21: extract → embed → VectorStore.add
    @Test
    @DisplayName("sediment → EmbeddingModel.embed + VectorStore.add 被调用")
    void shouldSedimentWithEmbeddingAndStore() {
        IssueClosure issue = createIssue("issue-002",
                "如何配置连接池?", "未配置最大连接数", "设置 max=20");

        service.sediment(issue);

        verify(embeddingModel).embed(anyString());
        assertThat(store.size()).isEqualTo(1);
    }

    // UC-22: userQuery > 60 chars → truncate
    @Test
    @DisplayName("userQuery > 60 字符 → title 截断至 60 + '...'")
    void shouldTruncateLongUserQuery() {
        StringBuilder longQueryBuilder = new StringBuilder();
        for (int i = 0; i < 80; i++) {
            longQueryBuilder.append("测");
        }
        String longQuery = longQueryBuilder.toString(); // 80 chars > 60
        IssueClosure issue = createIssue("issue-003", longQuery, "root cause", "solution");

        Document doc = service.extract(issue);

        assertThat(doc).isNotNull();
        // UC-22: title (stored as Document id) is truncated to "问题: " + 60 chars + "..."
        assertThat(doc.getId()).startsWith("问题: ");
        assertThat(doc.getId()).endsWith("...");
        assertThat(doc.getId()).isEqualTo("问题: " + longQuery.substring(0, 60) + "...");
        // Content keeps the FULL (untruncated) userQuery
        assertThat(doc.getContent()).contains(longQuery);
        assertThat(doc.getContent()).contains("## 问题");
    }

    // UC-23: selectedSolution 优先
    @Test
    @DisplayName("selectedSolution 存在 → content 含 selectedSolution 不含未选中选项")
    void shouldPreferSelectedSolution() {
        SolutionOption opt1 = new SolutionOption("opt-1", "方案1", "描述1", "medium", false);
        SolutionOption opt2 = new SolutionOption("opt-2", "方案2: 加索引", "描述2", "low", false);
        SolutionSuggestion suggestion = new SolutionSuggestion(
                Arrays.asList(opt1, opt2), "opt-2", null, null, null);

        IssueClosure issue = new IssueClosure("issue-004", null, null, null, null, "u1",
                "查询慢", "缺索引", suggestion, "方案2: 加索引",
                IssueStatus.CLOSED, null, null, null, null, null,
                System.currentTimeMillis(), System.currentTimeMillis());

        Document doc = service.extract(issue);

        assertThat(doc).isNotNull();
        assertThat(doc.getContent()).contains("方案2: 加索引");
        // Should NOT contain the unselected option as a list item
        assertThat(doc.getContent()).doesNotContain("[medium] 方案1");
    }

    // UC-04 (R4): missing rootCause → skip + warn
    @Test
    @DisplayName("rootCause 缺失 → 返回 null + 不写入 VectorStore")
    void shouldSkipWhenRootCauseMissing() {
        IssueClosure issue = new IssueClosure("issue-005", null, null, null, null, "u1",
                "query", null, null, null,
                IssueStatus.CLOSED, null, null, null, null, null,
                System.currentTimeMillis(), System.currentTimeMillis());

        Document doc = service.extract(issue);

        assertThat(doc).isNull();
        assertThat(store.size()).isEqualTo(0);
    }

    @Test
    @DisplayName("VectorStore=null → 不抛异常")
    void shouldHandleNullVectorStore() {
        service = new KnowledgeSedimentationService(null, embeddingModel);
        IssueClosure issue = createIssue("issue-006", "q", "r", "s");

        service.sediment(issue); // should not throw
    }

    @Test
    @DisplayName("EmbeddingModel=null → 仍写入 VectorStore (无 embedding)")
    void shouldHandleNullEmbeddingModel() {
        service = new KnowledgeSedimentationService(store, null);
        IssueClosure issue = createIssue("issue-007", "q", "r", "s");

        service.sediment(issue);

        assertThat(store.size()).isEqualTo(1);
    }
}
