package cn.watsontech.snapagent.boot2x.patrol;

import cn.watsontech.snapagent.core.agent.AgentExecutor;
import cn.watsontech.snapagent.core.agent.AgentTask;
import cn.watsontech.snapagent.core.agent.TaskStatus;
import cn.watsontech.snapagent.core.patrol.AlertConvergence;
import cn.watsontech.snapagent.core.patrol.AlertConverger;
import cn.watsontech.snapagent.core.patrol.AlertPushChannel;
import cn.watsontech.snapagent.core.patrol.AnomalyEvent;
import cn.watsontech.snapagent.core.patrol.AnomalyEventListener;
import cn.watsontech.snapagent.core.patrol.PatrolReport;
import cn.watsontech.snapagent.core.patrol.PatrolReportStore;
import cn.watsontech.snapagent.core.skill.SkillMeta;
import cn.watsontech.snapagent.core.skill.SkillRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Default {@link AnomalyEventListener} that records alerts and triggers diagnostic skills.
 *
 * <p>On receiving an {@link AnomalyEvent}:
 * <ol>
 *   <li>Records the alert via {@link AlertConverger} (if available)</li>
 *   <li>Determines the skill to trigger (from event, or defaults to "error-spike-investigation")</li>
 *   <li>Triggers the skill via {@link AgentExecutor}</li>
 *   <li>Stores the result as a {@link PatrolReport}</li>
 * </ol></p>
 */
public class DefaultAnomalyEventListener implements AnomalyEventListener {

    private static final Logger log = LoggerFactory.getLogger(DefaultAnomalyEventListener.class);

    private final AgentExecutor agentExecutor;
    private final SkillRegistry skillRegistry;
    private final AlertConverger alertConverger;
    private final PatrolReportStore reportStore;
    private final List<AlertPushChannel> pushChannels;

    public DefaultAnomalyEventListener(AgentExecutor agentExecutor,
                                       SkillRegistry skillRegistry,
                                       AlertConverger alertConverger,
                                       PatrolReportStore reportStore) {
        this(agentExecutor, skillRegistry, alertConverger, reportStore,
                Collections.<AlertPushChannel>emptyList());
    }

    public DefaultAnomalyEventListener(AgentExecutor agentExecutor,
                                       SkillRegistry skillRegistry,
                                       AlertConverger alertConverger,
                                       PatrolReportStore reportStore,
                                       List<AlertPushChannel> pushChannels) {
        this.agentExecutor = agentExecutor;
        this.skillRegistry = skillRegistry;
        this.alertConverger = alertConverger;
        this.reportStore = reportStore;
        this.pushChannels = pushChannels != null ? pushChannels
                : Collections.<AlertPushChannel>emptyList();
    }

    @Override
    public void onEvent(AnomalyEvent event) {
        log.info("Anomaly event received: type={}, source={}", event.getType(), event.getSource());
        long triggeredAt = System.currentTimeMillis();

        // Record alert if converger is available
        String alertId = null;
        if (alertConverger != null) {
            AlertConvergence alert = alertConverger.record(event);
            alertId = alert.getId();
        }

        // Determine skill to trigger
        String skillName = event.getSkillName();
        if (skillName == null || skillName.isEmpty()) {
            skillName = "error-spike-investigation";
        }

        // Build inputs from event
        Map<String, String> inputs = new LinkedHashMap<>();
        if (event.getInputs() != null) {
            inputs.putAll(event.getInputs());
        }
        inputs.put("_event_type", event.getType());
        inputs.put("_event_source", event.getSource());
        inputs.put("_event_message", event.getMessage());

        String patrolId = "event-" + (alertId != null ? alertId : String.valueOf(triggeredAt));

        try {
            SkillMeta skill = skillRegistry.get(skillName);
            if (skill == null) {
                log.error("Anomaly-triggered skill '{}' not found", skillName);
                if (reportStore != null) {
                    PatrolReport report = new PatrolReport(
                            null, patrolId, null, skillName, triggeredAt,
                            "FAILED", "Skill not found: " + skillName, true);
                    reportStore.save(report);
                    pushToChannels(report, event);
                }
                return;
            }

            AgentTask agentTask = AgentTask.create("patrol-user", skillName, inputs, null);
            agentExecutor.execute(agentTask, skill);

            TaskStatus status = agentTask.getStatus();
            String statusStr = status != null ? status.name() : "UNKNOWN";
            if (reportStore != null) {
                PatrolReport report = new PatrolReport(
                        null, patrolId, agentTask.getTaskId(),
                        skillName, triggeredAt, statusStr, event.getMessage(), true);
                reportStore.save(report);
                pushToChannels(report, event);
            }
        } catch (Exception e) {
            log.error("Anomaly-triggered diagnosis failed: {}", e.getMessage(), e);
            if (reportStore != null) {
                PatrolReport report = new PatrolReport(
                        null, patrolId, null,
                        skillName, triggeredAt, "FAILED", e.getMessage(), true);
                reportStore.save(report);
                pushToChannels(report, event);
            }
        }
    }

    private void pushToChannels(PatrolReport report, AnomalyEvent event) {
        if (pushChannels == null || pushChannels.isEmpty()) return;
        for (AlertPushChannel channel : pushChannels) {
            try {
                channel.push(report, event);
            } catch (Exception e) {
                log.error("Push channel '{}' failed (reportId={}): {}",
                        channel.type(), report.getId(), e.getMessage());
            }
        }
    }
}
