package cn.watsontech.snapagent.core.issue;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AcceptanceCriterionTest {

    @Test
    void constructor_setsAllFields() {
        AcceptanceCriterion ac = new AcceptanceCriterion("ac-1",
                "Allocation plan generated", "SELECT COUNT(*) FROM t_plan",
                "> 0", "mysql_query");
        assertThat(ac.getId()).isEqualTo("ac-1");
        assertThat(ac.getDescription()).isEqualTo("Allocation plan generated");
        assertThat(ac.getVerification()).isEqualTo("SELECT COUNT(*) FROM t_plan");
        assertThat(ac.getExpected()).isEqualTo("> 0");
        assertThat(ac.getTool()).isEqualTo("mysql_query");
    }

    @Test
    void toString_containsIdAndDescription() {
        AcceptanceCriterion ac = new AcceptanceCriterion("ac-2", "test desc", null, null, null);
        assertThat(ac.toString()).contains("ac-2").contains("test desc");
    }
}
