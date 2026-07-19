package cn.watsontech.snapagent.client.dto;

import java.util.Map;

public class RunRequest {
    private String skillName;
    private Map<String, String> inputs;

    public RunRequest() {}

    public RunRequest(String skillName, Map<String, String> inputs) {
        this.skillName = skillName;
        this.inputs = inputs;
    }

    public String getSkillName() { return skillName; }
    public void setSkillName(String skillName) { this.skillName = skillName; }
    public Map<String, String> getInputs() { return inputs; }
    public void setInputs(Map<String, String> inputs) { this.inputs = inputs; }
}
