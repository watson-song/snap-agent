package cn.watsontech.snapagent.boot2x.autoconfig;

import cn.watsontech.snapagent.core.cost.CostStore;
import cn.watsontech.snapagent.core.cost.CostTracker;
import cn.watsontech.snapagent.boot2x.cost.BudgetEnforcer;
import cn.watsontech.snapagent.boot2x.cost.CostCalculator;
import cn.watsontech.snapagent.boot2x.cost.CostSummaryService;
import cn.watsontech.snapagent.boot2x.cost.DefaultCostTracker;
import cn.watsontech.snapagent.boot2x.cost.FileCostStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

/**
 * Cost accounting auto-configuration — file-based cost store,
 * budget enforcement, cost tracking, summary service, and pricing calculator.
 *
 * <p>Active only when {@code snap-agent.cost.enabled=true}.</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "snap-agent.cost", name = "enabled", havingValue = "true")
public class CostAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CostAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(CostStore.class)
    public FileCostStore fileCostStore(SnapAgentProperties props) {
        String storageDir = props.getCost().getStorageDir();
        if (storageDir == null || storageDir.isEmpty()) {
            storageDir = props.getUploadSkillsDir() + "/cost";
        }
        log.info("FileCostStore assembled with storage dir: {}", storageDir);
        return new FileCostStore(storageDir);
    }

    @Bean
    @ConditionalOnMissingBean
    public BudgetEnforcer budgetEnforcer(CostStore costStore, SnapAgentProperties props) {
        SnapAgentProperties.Cost.Budgets budgets = props.getCost().getBudgets();
        BigDecimal perUser = budgets != null ? budgets.getPerUserDaily() : null;
        BigDecimal perSkill = budgets != null ? budgets.getPerSkillDaily() : null;
        BigDecimal global = budgets != null ? budgets.getGlobalDaily() : null;
        log.info("BudgetEnforcer assembled (perUserDaily={}, perSkillDaily={}, globalDaily={})",
                perUser, perSkill, global);
        return new BudgetEnforcer(costStore, perUser, perSkill, global);
    }

    @Bean
    @ConditionalOnMissingBean(CostTracker.class)
    public DefaultCostTracker defaultCostTracker(CostStore costStore, BudgetEnforcer budgetEnforcer) {
        log.info("DefaultCostTracker assembled");
        return new DefaultCostTracker(costStore, budgetEnforcer);
    }

    @Bean
    @ConditionalOnMissingBean
    public CostSummaryService costSummaryService(CostStore costStore, SnapAgentProperties props) {
        SnapAgentProperties.Cost.Budgets budgets = props.getCost().getBudgets();
        BigDecimal perUser = budgets != null ? budgets.getPerUserDaily() : null;
        BigDecimal perSkill = budgets != null ? budgets.getPerSkillDaily() : null;
        BigDecimal global = budgets != null ? budgets.getGlobalDaily() : null;
        log.info("CostSummaryService assembled");
        return new CostSummaryService(costStore, perUser, perSkill, global);
    }

    @Bean
    @ConditionalOnMissingBean(CostCalculator.class)
    public CostCalculator costCalculator(SnapAgentProperties props) {
        SnapAgentProperties.Cost.Pricing pricing = props.getCost().getPricing();
        BigDecimal input = pricing != null ? pricing.getInput() : BigDecimal.ZERO;
        BigDecimal output = pricing != null ? pricing.getOutput() : BigDecimal.ZERO;
        BigDecimal cacheRead = pricing != null ? pricing.getCacheRead() : BigDecimal.ZERO;
        log.info("CostCalculator assembled (input={}, output={}, cacheRead={})",
                input, output, cacheRead);
        return new CostCalculator(input, output, cacheRead);
    }
}
