package cn.watsontech.snapagent.boot2x.anchor;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link InjectionRequest} — Map parsing, source detection,
 * and source-id priority (TDD_SPEC GAP-3, UC-07).
 */
class InjectionRequestTest {

    // ---- fromMap ----

    @Test
    void shouldParseAllFieldsFromMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("anchorName", "公告区域");
        map.put("pageUrl", "/dashboard");
        map.put("skillId", "announcement");
        map.put("workflowId", "wf-001");
        map.put("cacheTtl", 1800);

        InjectionRequest req = InjectionRequest.fromMap(map);

        assertThat(req).isNotNull();
        assertThat(req.getAnchorName()).isEqualTo("公告区域");
        assertThat(req.getPageUrl()).isEqualTo("/dashboard");
        assertThat(req.getSkillId()).isEqualTo("announcement");
        assertThat(req.getWorkflowId()).isEqualTo("wf-001");
        assertThat(req.getCacheTtl()).isEqualTo(1800);
    }

    @Test
    void shouldReturnNullWhenMapIsNull() {
        assertThat(InjectionRequest.fromMap(null)).isNull();
    }

    @Test
    void shouldUseDefaultCacheTtlWhenNotSpecified() {
        Map<String, Object> map = new HashMap<>();
        map.put("anchorName", "公告");
        map.put("pageUrl", "/page");
        map.put("skillId", "skill1");

        InjectionRequest req = InjectionRequest.fromMap(map);

        assertThat(req.getCacheTtl()).isEqualTo(3600);
    }

    @Test
    void shouldParseCacheTtlAsInteger() {
        Map<String, Object> map = new HashMap<>();
        map.put("anchorName", "公告");
        map.put("pageUrl", "/page");
        map.put("skillId", "skill1");
        map.put("cacheTtl", 7200);

        InjectionRequest req = InjectionRequest.fromMap(map);

        assertThat(req.getCacheTtl()).isEqualTo(7200);
    }

    @Test
    void shouldParseCacheTtlAsLong() {
        Map<String, Object> map = new HashMap<>();
        map.put("anchorName", "公告");
        map.put("pageUrl", "/page");
        map.put("skillId", "skill1");
        map.put("cacheTtl", 7200L);

        InjectionRequest req = InjectionRequest.fromMap(map);

        assertThat(req.getCacheTtl()).isEqualTo(7200);
    }

    @Test
    void shouldHandleNonStringFieldsAsNull() {
        Map<String, Object> map = new HashMap<>();
        map.put("anchorName", 123);
        map.put("pageUrl", true);
        map.put("skillId", null);

        InjectionRequest req = InjectionRequest.fromMap(map);

        assertThat(req.getAnchorName()).isNull();
        assertThat(req.getPageUrl()).isNull();
        assertThat(req.getSkillId()).isNull();
    }

    // ---- hasSource ----

    @Test
    void shouldReturnTrueWhenOnlySkillIdProvided() {
        InjectionRequest req = new InjectionRequest(
                "公告", "/page", "skill1", null, 3600);
        assertThat(req.hasSource()).isTrue();
    }

    @Test
    void shouldReturnTrueWhenOnlyWorkflowIdProvided() {
        InjectionRequest req = new InjectionRequest(
                "公告", "/page", null, "wf1", 3600);
        assertThat(req.hasSource()).isTrue();
    }

    @Test
    void shouldReturnTrueWhenBothSourcesProvided() {
        InjectionRequest req = new InjectionRequest(
                "公告", "/page", "skill1", "wf1", 3600);
        assertThat(req.hasSource()).isTrue();
    }

    @Test
    void shouldReturnFalseWhenNoSourceProvided() {
        InjectionRequest req = new InjectionRequest(
                "公告", "/page", null, null, 3600);
        assertThat(req.hasSource()).isFalse();
    }

    @Test
    void shouldReturnFalseWhenSourcesAreEmptyStrings() {
        InjectionRequest req = new InjectionRequest(
                "公告", "/page", "", "", 3600);
        assertThat(req.hasSource()).isFalse();
    }

    // ---- getSourceId (skillId takes priority over workflowId) ----

    @Test
    void shouldReturnSkillIdWhenBothSourcesProvided() {
        InjectionRequest req = new InjectionRequest(
                "公告", "/page", "skill1", "wf1", 3600);
        assertThat(req.getSourceId()).isEqualTo("skill1");
    }

    @Test
    void shouldReturnWorkflowIdWhenOnlyWorkflowProvided() {
        InjectionRequest req = new InjectionRequest(
                "公告", "/page", null, "wf1", 3600);
        assertThat(req.getSourceId()).isEqualTo("wf1");
    }

    @Test
    void shouldReturnSkillIdWhenOnlySkillProvided() {
        InjectionRequest req = new InjectionRequest(
                "公告", "/page", "skill1", null, 3600);
        assertThat(req.getSourceId()).isEqualTo("skill1");
    }

    @Test
    void shouldReturnNullWhenNoSourceProvided() {
        InjectionRequest req = new InjectionRequest(
                "公告", "/page", null, null, 3600);
        assertThat(req.getSourceId()).isNull();
    }

    @Test
    void shouldReturnWorkflowIdWhenSkillIdIsEmptyString() {
        InjectionRequest req = new InjectionRequest(
                "公告", "/page", "", "wf1", 3600);
        assertThat(req.getSourceId()).isEqualTo("wf1");
    }
}
