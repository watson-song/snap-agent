package cn.watsontech.snapagent.boot2x.knowledge;

import cn.watsontech.snapagent.core.agent.AgentTask;
import cn.watsontech.snapagent.core.knowledge.KnowledgeBase;
import cn.watsontech.snapagent.core.knowledge.KnowledgeFragment;
import cn.watsontech.snapagent.core.knowledge.KnowledgeSearcher;
import cn.watsontech.snapagent.core.knowledge.KnowledgeSource;
import cn.watsontech.snapagent.core.skill.SkillAvailability;
import cn.watsontech.snapagent.core.skill.SkillMeta;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeInjectorTest {

    private KnowledgeFragment makeFragment(String title, String content) {
        return new KnowledgeFragment(title, content, title + ".md:section-1",
                Collections.<String, String>emptyMap());
    }

    private KnowledgeBase makeKnowledgeBase(List<KnowledgeFragment> fragments) {
        KnowledgeSource source = new KnowledgeSource() {
            private final List<KnowledgeFragment> frags = fragments;
            @Override
            public List<KnowledgeFragment> load() { return frags; }
            @Override
            public void reload() { }
            @Override
            public String type() { return "test"; }
        };
        KnowledgeSearcher searcher = new SimpleKeywordSearcher();
        return new KnowledgeBase(Collections.singletonList(source), searcher);
    }

    private AgentTask makeTask(String... inputValues) {
        Map<String, String> inputs = new LinkedHashMap<String, String>();
        for (int i = 0; i < inputValues.length; i++) {
            inputs.put("input" + i, inputValues[i]);
        }
        return AgentTask.create("user1", "test-skill", inputs, "test-model");
    }

    private SkillMeta makeSkill() {
        return new SkillMeta("test-skill", "test description",
                Collections.singletonList("mysql_query"),
                Collections.emptyList(), "## Phase 1\ntest body",
                SkillAvailability.AVAILABLE, null);
    }

    @Test
    void extend_matchingQuery_injectsKnowledgeSection() {
        KnowledgeBase kb = makeKnowledgeBase(Arrays.asList(
                makeFragment("Database Diagnostics",
                        "database connection pool and slow query analysis")));
        KnowledgeInjector injector = new KnowledgeInjector(kb, 3, 0.1);

        String result = injector.extend(makeSkill(), makeTask("database connection pool"));

        assertThat(result).isNotEmpty();
        assertThat(result).contains("业务知识参考");
        assertThat(result).contains("Database Diagnostics");
        assertThat(result).contains("database connection pool");
    }

    @Test
    void extend_noMatch_returnsEmptyString() {
        KnowledgeBase kb = makeKnowledgeBase(Arrays.asList(
                makeFragment("Redis Cache", "redis cache management")));
        KnowledgeInjector injector = new KnowledgeInjector(kb, 3, 0.1);

        String result = injector.extend(makeSkill(), makeTask("docker kubernetes"));

        assertThat(result).isEmpty();
    }

    @Test
    void extend_emptyInputs_returnsEmptyString() {
        KnowledgeBase kb = makeKnowledgeBase(Arrays.asList(
                makeFragment("Test", "test content")));
        KnowledgeInjector injector = new KnowledgeInjector(kb, 3, 0.1);

        String result = injector.extend(makeSkill(), makeTask());

        assertThat(result).isEmpty();
    }

    @Test
    void extend_nullTask_returnsEmptyString() {
        KnowledgeBase kb = makeKnowledgeBase(Arrays.asList(
                makeFragment("Test", "test content")));
        KnowledgeInjector injector = new KnowledgeInjector(kb, 3, 0.1);

        String result = injector.extend(makeSkill(), null);

        assertThat(result).isEmpty();
    }

    @Test
    void extend_respectsMaxFragmentsLimit() {
        // Create 5 fragments all matching "database"
        List<KnowledgeFragment> frags = new ArrayList<KnowledgeFragment>();
        for (int i = 1; i <= 5; i++) {
            frags.add(makeFragment("Database Topic " + i,
                    "database content number " + i));
        }
        KnowledgeBase kb = makeKnowledgeBase(frags);
        KnowledgeInjector injector = new KnowledgeInjector(kb, 2, 0.01);

        String result = injector.extend(makeSkill(), makeTask("database"));

        // Should only inject 2 fragments (maxFragments=2)
        assertThat(result).contains("知识片段 1");
        assertThat(result).contains("知识片段 2");
        assertThat(result).doesNotContain("知识片段 3");
    }

    @Test
    void extend_respectsMinScoreThreshold() {
        // High min score → no fragments match
        KnowledgeBase kb = makeKnowledgeBase(Arrays.asList(
                makeFragment("Redis", "redis cache tool")));
        KnowledgeInjector injector = new KnowledgeInjector(kb, 3, 0.99);

        String result = injector.extend(makeSkill(), makeTask("redis"));

        // score for "redis" in "Redis" title + content: title hits=1, content hits=1
        // score = (1*2 + 1) / (1*2) = 3/2 = 1.5 → clamped to 1.0
        // Actually 1.0 > 0.99, so it WILL match. Let me fix the test:
        // Use a query that only partially matches.
        // Let's use minScore=0.99 and a partial match query
        KnowledgeBase kb2 = makeKnowledgeBase(Arrays.asList(
                makeFragment("Redis Cache", "redis cache management tool")));
        KnowledgeInjector injector2 = new KnowledgeInjector(kb2, 3, 0.99);

        String result2 = injector2.extend(makeSkill(), makeTask("redis cache management extra words"));

        assertThat(result2).isEmpty();
    }

    @Test
    void extend_multipleInputValues_concatenatesAsQuery() {
        KnowledgeBase kb = makeKnowledgeBase(Arrays.asList(
                makeFragment("Database Pool", "database connection pool analysis")));
        KnowledgeInjector injector = new KnowledgeInjector(kb, 3, 0.1);

        // Two input values, keywords split across them
        String result = injector.extend(makeSkill(), makeTask("database", "connection pool"));

        assertThat(result).isNotEmpty();
        assertThat(result).contains("Database Pool");
    }

    @Test
    void extend_includesSourceMetadata() {
        KnowledgeBase kb = makeKnowledgeBase(Arrays.asList(
                makeFragment("My Topic", "my content here")));
        KnowledgeInjector injector = new KnowledgeInjector(kb, 3, 0.01);

        String result = injector.extend(makeSkill(), makeTask("my topic"));

        assertThat(result).contains("来源:");
        assertThat(result).contains("My Topic.md:section-1");
    }

    @Test
    void extend_chineseQuery_matchesChineseFragments() {
        KnowledgeBase kb = makeKnowledgeBase(Arrays.asList(
                makeFragment("补货策略", "补货策略生成规则和参数配置")));
        KnowledgeInjector injector = new KnowledgeInjector(kb, 3, 0.1);

        String result = injector.extend(makeSkill(), makeTask("补货策略怎么配置"));

        assertThat(result).isNotEmpty();
        assertThat(result).contains("补货策略");
    }
}
