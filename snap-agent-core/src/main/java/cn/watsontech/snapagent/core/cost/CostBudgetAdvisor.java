package cn.watsontech.snapagent.core.cost;

import cn.watsontech.snapagent.core.graph.GraphState;
import cn.watsontech.snapagent.core.graph.StateKeys;
import cn.watsontech.snapagent.core.graph.advisor.Advisor;
import cn.watsontech.snapagent.core.graph.execution.ExecutionContext;
import cn.watsontech.snapagent.core.graph.hitl.InterruptException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

/**
 * Advisor (order=300) that checks three-dimension daily budgets before node
 * execution and records LLM cost after node execution.
 *
 * <p>Before: checks perUserDaily, perSkillDaily, globalDaily budgets.
 * If any is exceeded, throws InterruptException to pause graph execution.</p>
 *
 * <p>After: reads token counts from state (llm.input_tokens, llm.output_tokens,
 * llm.cache_read_tokens, llm.model) and records a CostRecord via CostStore.
 * If CostStore.save throws, logs WARN and does not interrupt execution.</p>
 */
public class CostBudgetAdvisor implements Advisor {

    private static final Logger log = LoggerFactory.getLogger(CostBudgetAdvisor.class);

    private final CostStore costStore;
    private final CostCalculator costCalculator;
    private BigDecimal perUserDaily;
    private BigDecimal perSkillDaily;
    private BigDecimal globalDaily;

    public CostBudgetAdvisor(CostStore costStore) {
        this(costStore, (input, output, cache) -> BigDecimal.ZERO);
    }

    public CostBudgetAdvisor(CostStore costStore, CostCalculator costCalculator) {
        this.costStore = costStore;
        this.costCalculator = costCalculator;
    }

    public void setPerUserDaily(BigDecimal perUserDaily) { this.perUserDaily = perUserDaily; }
    public void setPerSkillDaily(BigDecimal perSkillDaily) { this.perSkillDaily = perSkillDaily; }
    public void setGlobalDaily(BigDecimal globalDaily) { this.globalDaily = globalDaily; }

    @Override
    public int getOrder() { return 300; }

    @Override
    public String getName() { return "cost-budget"; }

    @Override
    public GraphState beforeNode(String nodeName, GraphState state, Object ctxObj) throws InterruptException {
        if (costStore == null) {
            return state;
        }
        ExecutionContext ctx = (ExecutionContext) ctxObj;
        String userId = ctx.getUserId();
        String skillName = ctx.getSkillName();

        long[] range = todayRange();
        long from = range[0];
        long to = range[1];

        if (perUserDaily != null && userId != null) {
            BigDecimal userCost = costStore.sumCostByUser(userId, from, to);
            if (userCost.compareTo(perUserDaily) >= 0) {
                log.info("Budget exceeded for user {}: {} >= {}", userId, userCost, perUserDaily);
                Map<String, Object> payload = new HashMap<>();
                payload.put("dimension", "user");
                payload.put("userId", userId);
                payload.put("cost", userCost);
                payload.put("limit", perUserDaily);
                throw new InterruptException("Budget exceeded for user " + userId, payload);
            }
        }

        if (perSkillDaily != null && skillName != null) {
            BigDecimal skillCost = costStore.sumCostBySkill(skillName, from, to);
            if (skillCost.compareTo(perSkillDaily) >= 0) {
                log.info("Budget exceeded for skill {}: {} >= {}", skillName, skillCost, perSkillDaily);
                Map<String, Object> payload = new HashMap<>();
                payload.put("dimension", "skill");
                payload.put("skillName", skillName);
                payload.put("cost", skillCost);
                payload.put("limit", perSkillDaily);
                throw new InterruptException("Budget exceeded for skill " + skillName, payload);
            }
        }

        if (globalDaily != null) {
            BigDecimal globalCost = costStore.sumCost(from, to);
            if (globalCost.compareTo(globalDaily) >= 0) {
                log.info("Global budget exceeded: {} >= {}", globalCost, globalDaily);
                Map<String, Object> payload = new HashMap<>();
                payload.put("dimension", "global");
                payload.put("cost", globalCost);
                payload.put("limit", globalDaily);
                throw new InterruptException("Global budget exceeded", payload);
            }
        }

        return state;
    }

    @Override
    public GraphState afterNode(String nodeName, GraphState state, Object ctxObj) {
        if (costStore == null) {
            return state;
        }

        Integer inputTokens = state.get(StateKeys.LLM_INPUT_TOKENS);
        Integer outputTokens = state.get(StateKeys.LLM_OUTPUT_TOKENS);
        if (inputTokens == null && outputTokens == null) {
            return state;
        }

        ExecutionContext ctx = (ExecutionContext) ctxObj;
        int input = inputTokens != null ? inputTokens : 0;
        int output = outputTokens != null ? outputTokens : 0;
        Integer cacheRead = state.get(StateKeys.LLM_CACHE_READ_TOKENS);
        int cache = cacheRead != null ? cacheRead : 0;
        String model = state.get(StateKeys.LLM_MODEL);

        BigDecimal cost = costCalculator.computeCost(input, output, cache);

        CostRecord record = new CostRecord(
                null,
                ctx.getUserId(),
                ctx.getSkillName(),
                ctx.getTaskId(),
                model,
                input,
                output,
                cache,
                cost,
                System.currentTimeMillis()
        );

        try {
            costStore.save(record);
        } catch (RuntimeException e) {
            log.warn("CostStore.save failed, cost record lost", e);
        }

        return state;
    }

    private long[] todayRange() {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long from = cal.getTimeInMillis();
        cal.set(Calendar.HOUR_OF_DAY, 23);
        cal.set(Calendar.MINUTE, 59);
        cal.set(Calendar.SECOND, 59);
        cal.set(Calendar.MILLISECOND, 999);
        long to = cal.getTimeInMillis();
        return new long[]{from, to};
    }
}
