package cn.watsontech.snapagent.core.cost;

import cn.watsontech.snapagent.core.graph.GraphState;
import cn.watsontech.snapagent.core.graph.execution.ExecutionContext;
import cn.watsontech.snapagent.core.graph.hitl.InterruptException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("CostBudgetAdvisor — 三维预算检查 + 成本记录")
class CostBudgetAdvisorTest {

    private ExecutionContext mockCtx(String userId, String skillName) {
        ExecutionContext ctx = mock(ExecutionContext.class);
        when(ctx.getUserId()).thenReturn(userId);
        when(ctx.getSkillName()).thenReturn(skillName);
        when(ctx.getTaskId()).thenReturn("t-1");
        return ctx;
    }

    // UC-01: before 三维预算检查
    @Test
    @DisplayName("perUserDaily 超额 → InterruptException")
    void shouldThrowWhenPerUserBudgetExceeded() {
        CostStore store = mock(CostStore.class);
        when(store.sumCostByUser(eq("u1"), anyLong(), anyLong())).thenReturn(new BigDecimal("10.00"));

        CostBudgetAdvisor advisor = new CostBudgetAdvisor(store);
        advisor.setPerUserDaily(new BigDecimal("10.00"));

        ExecutionContext ctx = mockCtx("u1", "skill1");
        assertThatThrownBy(() -> advisor.beforeNode("agent", GraphState.empty("t1"), ctx))
                .isInstanceOf(InterruptException.class)
                .hasMessageContaining("Budget exceeded for user u1");
    }

    @Test
    @DisplayName("perSkillDaily 超额 → InterruptException")
    void shouldThrowWhenPerSkillBudgetExceeded() {
        CostStore store = mock(CostStore.class);
        when(store.sumCostBySkill(eq("log-analysis"), anyLong(), anyLong())).thenReturn(new BigDecimal("50.00"));

        CostBudgetAdvisor advisor = new CostBudgetAdvisor(store);
        advisor.setPerSkillDaily(new BigDecimal("50.00"));

        ExecutionContext ctx = mockCtx("u1", "log-analysis");
        assertThatThrownBy(() -> advisor.beforeNode("agent", GraphState.empty("t1"), ctx))
                .isInstanceOf(InterruptException.class)
                .hasMessageContaining("Budget exceeded for skill log-analysis");
    }

    @Test
    @DisplayName("globalDaily 超额 → InterruptException")
    void shouldThrowWhenGlobalBudgetExceeded() {
        CostStore store = mock(CostStore.class);
        when(store.sumCost(anyLong(), anyLong())).thenReturn(new BigDecimal("200.00"));

        CostBudgetAdvisor advisor = new CostBudgetAdvisor(store);
        advisor.setGlobalDaily(new BigDecimal("200.00"));

        ExecutionContext ctx = mockCtx("u1", "skill1");
        assertThatThrownBy(() -> advisor.beforeNode("agent", GraphState.empty("t1"), ctx))
                .isInstanceOf(InterruptException.class)
                .hasMessageContaining("Global budget exceeded");
    }

    @Test
    @DisplayName("三维均 null → 不抛异常")
    void shouldNotThrowWhenAllBudgetsNull() throws Exception {
        CostStore store = mock(CostStore.class);
        CostBudgetAdvisor advisor = new CostBudgetAdvisor(store);

        ExecutionContext ctx = mockCtx("u1", "skill1");
        GraphState result = advisor.beforeNode("agent", GraphState.empty("t1"), ctx);
        assertThat(result).isNotNull();
        verify(store, never()).sumCostByUser(anyString(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("perUserDaily 未达上限 → 正常执行")
    void shouldPassWhenBudgetNotExceeded() throws Exception {
        CostStore store = mock(CostStore.class);
        when(store.sumCostByUser(eq("u1"), anyLong(), anyLong())).thenReturn(new BigDecimal("5.00"));

        CostBudgetAdvisor advisor = new CostBudgetAdvisor(store);
        advisor.setPerUserDaily(new BigDecimal("10.00"));

        ExecutionContext ctx = mockCtx("u1", "skill1");
        GraphState result = advisor.beforeNode("agent", GraphState.empty("t1"), ctx);
        assertThat(result).isNotNull();
    }

    // UC-02: after 记录成本
    @Test
    @DisplayName("LLM 调用消耗 tokens → CostStore.save 被调用")
    void shouldRecordCostWhenTokensPresent() {
        CostStore store = mock(CostStore.class);
        CostCalculator calculator = (input, output, cache) -> new BigDecimal("0.05");
        CostBudgetAdvisor advisor = new CostBudgetAdvisor(store, calculator);

        GraphState state = GraphState.empty("t1")
                .with("llm.input_tokens", 100)
                .with("llm.output_tokens", 200)
                .with("llm.cache_read_tokens", 50)
                .with("llm.model", "claude-sonnet-4");

        ExecutionContext ctx = mockCtx("u1", "log-analysis");
        advisor.afterNode("agent", state, ctx);

        verify(store).save(argThat(record ->
                record.getUserId().equals("u1") &&
                record.getSkillName().equals("log-analysis") &&
                record.getModel().equals("claude-sonnet-4") &&
                record.getInputTokens() == 100 &&
                record.getOutputTokens() == 200 &&
                record.getCacheReadTokens() == 50 &&
                record.getCost().compareTo(new BigDecimal("0.05")) == 0
        ));
    }

    @Test
    @DisplayName("无 token 消耗 → CostStore.save 不被调用")
    void shouldNotRecordWhenNoTokens() {
        CostStore store = mock(CostStore.class);
        CostBudgetAdvisor advisor = new CostBudgetAdvisor(store);

        GraphState state = GraphState.empty("t1");
        ExecutionContext ctx = mockCtx("u1", "skill1");
        advisor.afterNode("agent", state, ctx);

        verify(store, never()).save(any());
    }

    // UC-03: 异常隔离
    @Test
    @DisplayName("CostStore.save 抛异常 → catch + WARN，不中断")
    void shouldCatchExceptionWhenCostStoreFails() {
        CostStore store = mock(CostStore.class);
        doThrow(new RuntimeException("DB down")).when(store).save(any());
        CostCalculator calculator = (input, output, cache) -> new BigDecimal("0.01");
        CostBudgetAdvisor advisor = new CostBudgetAdvisor(store, calculator);

        GraphState state = GraphState.empty("t1")
                .with("llm.input_tokens", 100)
                .with("llm.output_tokens", 200);

        ExecutionContext ctx = mockCtx("u1", "skill1");
        GraphState result = advisor.afterNode("agent", state, ctx);
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("CostStore=null → beforeNode 不抛异常")
    void shouldHandleNullCostStore() throws Exception {
        CostBudgetAdvisor advisor = new CostBudgetAdvisor(null);
        ExecutionContext ctx = mockCtx("u1", "skill1");
        GraphState result = advisor.beforeNode("agent", GraphState.empty("t1"), ctx);
        assertThat(result).isNotNull();
    }
}
