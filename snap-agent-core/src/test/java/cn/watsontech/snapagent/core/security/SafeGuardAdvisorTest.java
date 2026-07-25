package cn.watsontech.snapagent.core.security;

import cn.watsontech.snapagent.core.graph.GraphState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SafeGuardAdvisor — 内容审核")
class SafeGuardAdvisorTest {

    // UC-18: before 过滤 prompt
    @Test
    @DisplayName("before 过滤敏感词 — 密码被替换")
    void shouldReplaceSensitiveWordInPrompt() {
        SafeGuardAdvisor advisor = new SafeGuardAdvisor(Arrays.asList("密码", "身份证"));
        GraphState state = GraphState.empty("t1").with("user.query", "我的密码是123");

        GraphState result = advisor.beforeNode("agent", state, null);

        assertThat((String) result.get("user.query")).isEqualTo("我的***是123");
    }

    @Test
    @DisplayName("替换后不抛 InterruptException — 不中断执行")
    void shouldNotThrowAfterReplacement() {
        SafeGuardAdvisor advisor = new SafeGuardAdvisor(Arrays.asList("密码"));
        GraphState state = GraphState.empty("t1").with("user.query", "我的密码是");

        GraphState result = advisor.beforeNode("agent", state, null);
        assertThat((String) result.get("user.query")).contains("***");
    }

    @Test
    @DisplayName("user.query 不存在 → 不抛异常")
    void shouldHandleNullQuery() {
        SafeGuardAdvisor advisor = new SafeGuardAdvisor(Arrays.asList("密码"));
        GraphState state = GraphState.empty("t1");

        GraphState result = advisor.beforeNode("agent", state, null);
        assertThat(result).isNotNull();
    }

    // UC-19: after 过滤 LLM 输出
    @Test
    @DisplayName("after 过滤 LLM 输出 — 身份证号被脱敏")
    void shouldSanitizeLlmOutput() {
        SafeGuardAdvisor advisor = new SafeGuardAdvisor(Arrays.asList("身份证"));
        GraphState state = GraphState.empty("t1").with("thought", "身份证号: 110101199001011234");

        GraphState result = advisor.afterNode("agent", state, null);

        assertThat((String) result.get("thought")).isEqualTo("***号: 110101199001011234");
    }

    @Test
    @DisplayName("thought 不存在 → 不抛异常")
    void shouldHandleNullThought() {
        SafeGuardAdvisor advisor = new SafeGuardAdvisor(Arrays.asList("密码"));
        GraphState state = GraphState.empty("t1");

        GraphState result = advisor.afterNode("agent", state, null);
        assertThat(result).isNotNull();
    }

    // UC-20: 白名单优先
    @Test
    @DisplayName("白名单词包含敏感词 → 不替换")
    void shouldNotReplaceWhenWhitelisted() {
        SafeGuardAdvisor advisor = new SafeGuardAdvisor(
                Arrays.asList("文档"), Arrays.asList("技术文档"), "***");
        GraphState state = GraphState.empty("t1").with("user.query", "请查看技术文档");

        GraphState result = advisor.beforeNode("agent", state, null);

        assertThat((String) result.get("user.query")).isEqualTo("请查看技术文档");
    }

    @Test
    @DisplayName("白名单不匹配 → 正常替换")
    void shouldReplaceWhenNotWhitelisted() {
        SafeGuardAdvisor advisor = new SafeGuardAdvisor(
                Arrays.asList("文档"), Arrays.asList("技术文档"), "***");
        GraphState state = GraphState.empty("t1").with("user.query", "请查看文档");

        GraphState result = advisor.beforeNode("agent", state, null);

        assertThat((String) result.get("user.query")).isEqualTo("请查看***");
    }

    // UC-21: 异常隔离
    @Test
    @DisplayName("空敏感词列表 → 不替换不抛异常")
    void shouldHandleEmptySensitiveWords() {
        SafeGuardAdvisor advisor = new SafeGuardAdvisor(Collections.<String>emptyList());
        GraphState state = GraphState.empty("t1").with("user.query", "hello world");

        GraphState result = advisor.beforeNode("agent", state, null);
        assertThat((String) result.get("user.query")).isEqualTo("hello world");
    }

    @Test
    @DisplayName("order=50")
    void shouldReturnOrder50() {
        SafeGuardAdvisor advisor = new SafeGuardAdvisor(Collections.<String>emptyList());
        assertThat(advisor.getOrder()).isEqualTo(50);
    }
}
