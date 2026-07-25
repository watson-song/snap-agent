package cn.watsontech.snapagent.core.graph.react;

import cn.watsontech.snapagent.core.agent.AgentTask;
import cn.watsontech.snapagent.core.graph.CompiledGraph;
import cn.watsontech.snapagent.core.graph.GraphState;
import cn.watsontech.snapagent.core.graph.advisor.Advisor;
import cn.watsontech.snapagent.core.graph.advisor.AdvisorNode;
import cn.watsontech.snapagent.core.skill.SkillAvailability;
import cn.watsontech.snapagent.core.skill.SkillMeta;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReActGraphFactory 标准 ReAct 拓扑构建")
class ReActGraphFactoryTest {

    private SkillMeta testSkill() {
        return new SkillMeta(
            "test-skill",                  // name
            "test description",             // description
            Collections.<String>emptyList(), // tools
            null,                           // inputs
            "test body",                    // body
            SkillAvailability.AVAILABLE,    // availability
            null                            // unavailableReason
        );
    }

    private AgentTask testTask() {
        return new AgentTask(
            "t1",                           // taskId
            "u1",                           // userId
            "test-skill",                   // skillId
            new HashMap<String, String>(),  // inputs
            "test-model"                    // model
        );
    }

    @Test
    @DisplayName("工厂生成标准 ReAct 拓扑: entry→agent↔tools→END")
    void standardReActTopology() {
        SkillMeta skill = testSkill();
        AgentTask task = testTask();

        ReActGraphFactory factory = new ReActGraphFactory();
        CompiledGraph graph = factory.build(skill, task, Collections.<Advisor>emptyList());

        assertThat(graph.getEntryPoint()).isEqualTo("entry");
        assertThat(graph.getNodes()).containsKeys("entry", "agent", "tools", "END");
        assertThat(graph.getEdgesFrom("entry")).isNotEmpty();
        assertThat(graph.getEdgesFrom("tools")).isNotEmpty();
    }

    @Test
    @DisplayName("advisors 被包裹为 AdvisorNode")
    void advisorsWrappedInAdvisorNode() {
        SkillMeta skill = testSkill();
        AgentTask task = testTask();

        Advisor advisor1 = new TestAdvisor(200);
        Advisor advisor2 = new TestAdvisor(50);

        ReActGraphFactory factory = new ReActGraphFactory();
        CompiledGraph graph = factory.build(skill, task, new ArrayList<>(Arrays.asList(advisor1, advisor2)));

        // Nodes should be AdvisorNode instances
        assertThat(graph.getNodes().get("entry")).isInstanceOf(AdvisorNode.class);
        assertThat(graph.getNodes().get("agent")).isInstanceOf(AdvisorNode.class);
    }

    @Test
    @DisplayName("无 advisors 仍可构建")
    void noAdvisorsStillWorks() {
        SkillMeta skill = testSkill();
        AgentTask task = testTask();

        ReActGraphFactory factory = new ReActGraphFactory();
        CompiledGraph graph = factory.build(skill, task, Collections.<Advisor>emptyList());
        assertThat(graph.getNodes()).containsKeys("entry", "agent", "tools", "END");
    }

    static class TestAdvisor implements Advisor {
        private final int order;
        TestAdvisor(int order) { this.order = order; }
        @Override public int getOrder() { return order; }
        @Override public String getName() { return "test-advisor"; }
        @Override public GraphState beforeNode(String nodeName, GraphState state, Object ctx) { return state; }
        @Override public GraphState afterNode(String nodeName, GraphState state, Object ctx) { return state; }
    }
}
