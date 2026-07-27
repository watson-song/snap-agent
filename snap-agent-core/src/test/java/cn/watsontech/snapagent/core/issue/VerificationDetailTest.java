package cn.watsontech.snapagent.core.issue;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VerificationDetailTest {

    @Test
    void constructor_setsAllFields() {
        VerificationDetail vd = new VerificationDetail("ac-1", "desc", true, "3", "> 0");
        assertThat(vd.getCriterionId()).isEqualTo("ac-1");
        assertThat(vd.getDescription()).isEqualTo("desc");
        assertThat(vd.isPassed()).isTrue();
        assertThat(vd.getActual()).isEqualTo("3");
        assertThat(vd.getExpected()).isEqualTo("> 0");
    }

    @Test
    void toString_containsIdAndPassed() {
        VerificationDetail vd = new VerificationDetail("ac-1", "desc", false, null, null);
        assertThat(vd.toString()).contains("ac-1").contains("passed=false");
    }
}
