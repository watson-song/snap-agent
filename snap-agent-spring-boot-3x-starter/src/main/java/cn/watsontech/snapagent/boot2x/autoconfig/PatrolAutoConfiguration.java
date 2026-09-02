package cn.watsontech.snapagent.boot2x.autoconfig;

import cn.watsontech.snapagent.boot2x.agent.AgentService;
import cn.watsontech.snapagent.core.patrol.AlertConverger;
import cn.watsontech.snapagent.core.patrol.AlertPushChannel;
import cn.watsontech.snapagent.core.patrol.PatrolLockProvider;
import cn.watsontech.snapagent.core.patrol.PatrolReportStore;
import cn.watsontech.snapagent.boot2x.patrol.DefaultAnomalyEventListener;
import cn.watsontech.snapagent.boot2x.patrol.EmailAlertPushChannel;
import cn.watsontech.snapagent.boot2x.patrol.InMemoryAlertConverger;
import cn.watsontech.snapagent.boot2x.patrol.InMemoryPatrolReportStore;
import cn.watsontech.snapagent.boot2x.patrol.NoopPatrolLockProvider;
import cn.watsontech.snapagent.boot2x.patrol.ScheduledPatrolScheduler;
import cn.watsontech.snapagent.boot2x.patrol.TemplateBugfixSuggester;
import cn.watsontech.snapagent.boot2x.patrol.WebhookAlertPushChannel;
import cn.watsontech.snapagent.core.skill.SkillRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.ArrayList;
import java.util.List;

/**
 * Patrol and alert auto-configuration.
 *
 * <p>Patrol beans (report store, lock provider, scheduler, listener) are active
 * when {@code snap-agent.patrol.enabled=true}. Alert convergence is active
 * when {@code snap-agent.alert.enabled=true}. Push channels have their own
 * conditions. {@code TemplateBugfixSuggester} is always active.</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "snap-agent", name = "enabled", havingValue = "true")
public class PatrolAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PatrolAutoConfiguration.class);

    // ---- InMemoryPatrolReportStore (v0.5, SPI-extracted v1.1) ----
    @Bean
    @ConditionalOnProperty(prefix = "snap-agent.patrol", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public PatrolReportStore patrolReportStore(SnapAgentProperties props) {
        log.info("InMemoryPatrolReportStore assembled (buffer-size={})", props.getPatrol().getReportBufferSize());
        return new InMemoryPatrolReportStore(props.getPatrol().getReportBufferSize());
    }

    // ---- NoopPatrolLockProvider (v1.1, multi-Pod coordination SPI) ----
    @Bean
    @ConditionalOnProperty(prefix = "snap-agent.patrol", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public PatrolLockProvider patrolLockProvider() {
        log.info("NoopPatrolLockProvider assembled (single-Pod mode; implement PatrolLockProvider for multi-Pod)");
        return new NoopPatrolLockProvider();
    }

    // ---- Patrol TaskScheduler (v0.5) ----
    @Bean
    @ConditionalOnProperty(prefix = "snap-agent.patrol", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(name = "patrolTaskScheduler")
    public ThreadPoolTaskScheduler patrolTaskScheduler(SnapAgentProperties props) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(props.getPatrol().getSchedulerPoolSize());
        scheduler.setThreadNamePrefix("patrol-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.initialize();
        log.info("patrolTaskScheduler initialized (pool-size={})", props.getPatrol().getSchedulerPoolSize());
        return scheduler;
    }

    // ---- ScheduledPatrolScheduler (v0.5) ----
    @Bean
    @ConditionalOnProperty(prefix = "snap-agent.patrol", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public ScheduledPatrolScheduler scheduledPatrolScheduler(
            TaskScheduler patrolTaskScheduler,
            AgentService agentService,
            SkillRegistry skillRegistry,
            PatrolReportStore patrolReportStore,
            PatrolLockProvider patrolLockProvider,
            SnapAgentProperties props,
            ObjectProvider<AlertPushChannel> pushChannelProvider,
            ObjectProvider<AlertConverger> alertConvergerProvider) {
        List<AlertPushChannel> pushChannels = new ArrayList<AlertPushChannel>();
        pushChannels.addAll(pushChannelProvider.orderedStream()
                .collect(java.util.stream.Collectors.toList()));
        AlertConverger alertConverger = alertConvergerProvider.getIfAvailable();
        log.info("ScheduledPatrolScheduler assembled (lockProvider={}, pushChannels={}, lockTtl={}s, alertConverger={})",
                patrolLockProvider.type(), pushChannels.size(), props.getPatrol().getLockTtlSeconds(),
                alertConverger != null ? alertConverger.getClass().getSimpleName() : "none");
        return new ScheduledPatrolScheduler(
                patrolTaskScheduler, agentService, skillRegistry, patrolReportStore,
                patrolLockProvider, props.getPatrol().getLockTtlSeconds(), pushChannels, alertConverger);
    }

    // ---- InMemoryAlertConverger (v0.5) ----
    @Bean
    @ConditionalOnProperty(prefix = "snap-agent.alert", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public InMemoryAlertConverger inMemoryAlertConverger(SnapAgentProperties props) {
        log.info("InMemoryAlertConverger assembled (buffer-size={}, auto-resolve-minutes={})",
                props.getAlert().getBufferSize(), props.getAlert().getAutoResolveMinutes());
        return new InMemoryAlertConverger(
                props.getAlert().getBufferSize(),
                props.getAlert().getAutoResolveMinutes());
    }

    // ---- DefaultAnomalyEventListener (v0.5) ----
    @Bean
    @ConditionalOnProperty(prefix = "snap-agent.patrol", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public DefaultAnomalyEventListener defaultAnomalyEventListener(
            AgentService agentService,
            SkillRegistry skillRegistry,
            ObjectProvider<AlertConverger> alertConvergerProvider,
            PatrolReportStore patrolReportStore,
            ObjectProvider<AlertPushChannel> pushChannelProvider) {
        List<AlertPushChannel> pushChannels = new ArrayList<AlertPushChannel>();
        pushChannels.addAll(pushChannelProvider.orderedStream()
                .collect(java.util.stream.Collectors.toList()));
        log.info("DefaultAnomalyEventListener assembled (pushChannels={})", pushChannels.size());
        return new DefaultAnomalyEventListener(
                agentService, skillRegistry, alertConvergerProvider.getIfAvailable(),
                patrolReportStore, pushChannels);
    }

    // ---- WebhookAlertPushChannel (v1.1, default push channel) ----
    @Bean
    @ConditionalOnExpression(
            "${snap-agent.alert.push.webhook.enabled:false} and " +
            "!T(org.springframework.util.StringUtils).isEmpty('${snap-agent.alert.push.webhook.url:}')")
    @ConditionalOnMissingBean
    public WebhookAlertPushChannel webhookAlertPushChannel(SnapAgentProperties props) {
        SnapAgentProperties.Alert.Push.Webhook wh = props.getAlert().getPush().getWebhook();
        log.info("WebhookAlertPushChannel assembled (url={}, connectMs={}, readMs={})",
                wh.getUrl(), wh.getConnectTimeoutMs(), wh.getReadTimeoutMs());
        return new WebhookAlertPushChannel(
                wh.getUrl(), wh.getAuthHeader(), wh.getAuthToken(),
                wh.getConnectTimeoutMs(), wh.getReadTimeoutMs());
    }

    // ---- EmailAlertPushChannel (v1.1, default push channel; requires spring-boot-starter-mail) ----
    @Bean
    @ConditionalOnProperty(
            prefix = "snap-agent.alert.push.email", name = "enabled", havingValue = "true")
    @ConditionalOnClass(name = "org.springframework.mail.javamail.JavaMailSender")
    @ConditionalOnMissingBean
    public Object emailAlertPushChannel(
            SnapAgentProperties props,
            ObjectProvider<org.springframework.mail.javamail.JavaMailSender> mailSenderProvider) {
        SnapAgentProperties.Alert.Push.Email emailCfg = props.getAlert().getPush().getEmail();
        org.springframework.mail.javamail.JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            log.warn("snap-agent.alert.push.email.enabled=true but no JavaMailSender bean found; " +
                    "add spring-boot-starter-mail to host dependencies to enable email push");
            return new NoopMarkerBean("emailAlertPushChannel-skipped");
        }
        log.info("EmailAlertPushChannel assembled (recipients={}, from={})",
                emailCfg.getTo(), emailCfg.getFrom());
        return new EmailAlertPushChannel(
                mailSender, emailCfg.getFrom(), emailCfg.getTo(), emailCfg.getSubjectPrefix());
    }

    // ---- TemplateBugfixSuggester (v0.5) ----
    @Bean
    @ConditionalOnMissingBean
    public TemplateBugfixSuggester templateBugfixSuggester() {
        log.info("TemplateBugfixSuggester assembled");
        return new TemplateBugfixSuggester();
    }
}
