package cn.watsontech.snapagent.boot2x.autoconfig;

import cn.watsontech.snapagent.boot2x.security.AuditStoreAuditLogger;
import cn.watsontech.snapagent.boot2x.security.DefaultPrincipalResolver;
import cn.watsontech.snapagent.boot2x.security.InMemoryAuditStore;
import cn.watsontech.snapagent.boot2x.security.SpringSecurityAdapter;
import cn.watsontech.snapagent.boot2x.tool.SqlGuard;
import cn.watsontech.snapagent.core.security.AuditStore;
import cn.watsontech.snapagent.core.security.PrincipalResolver;
import cn.watsontech.snapagent.core.security.SecurityAuditLogger;
import cn.watsontech.snapagent.core.security.SecurityGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Security-related auto-configuration extracted from {@link SnapAgentAutoConfiguration}.
 *
 * <p>Activated only when {@code snap-agent.enabled=true} (default false). Provides
 * the SqlGuard, PrincipalResolver, AuditStore, SecurityAuditLogger, and the
 * SecurityGateway adapter (Spring Security primary, Shiro fallback) beans.</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "snap-agent", name = "enabled", havingValue = "true")
public class SecurityAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SecurityAutoConfiguration.class);

    // ---- SqlGuard ----
    @Bean
    @ConditionalOnMissingBean
    public SqlGuard sqlGuard(SnapAgentProperties props) {
        return new SqlGuard(props.getAgent().getMaxResultRows());
    }

    // ---- PrincipalResolver ----
    @Bean
    @ConditionalOnMissingBean
    public PrincipalResolver principalResolver() {
        return new DefaultPrincipalResolver();
    }

    // ---- AuditStore (in-memory ring buffer) ----
    @Bean
    @ConditionalOnMissingBean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "snap-agent.security", name = "audit-log", havingValue = "true", matchIfMissing = true)
    public AuditStore auditStore() {
        log.info("Using InMemoryAuditStore (capacity=1000) for audit entries");
        return new InMemoryAuditStore(1000);
    }

    // ---- SecurityAuditLogger (bridged to AuditStore + SLF4J) ----
    @Bean
    @ConditionalOnMissingBean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "snap-agent.security", name = "audit-log", havingValue = "true", matchIfMissing = true)
    public SecurityAuditLogger securityAuditLogger(AuditStore auditStore) {
        log.info("Using AuditStoreAuditLogger (audit store + SLF4J)");
        return new AuditStoreAuditLogger(auditStore);
    }

    // ---- SecurityGateway: Spring Security adapter ----
    @Bean
    @ConditionalOnClass(name = "org.springframework.security.core.context.SecurityContextHolder")
    @ConditionalOnMissingBean(SecurityGateway.class)
    public SecurityGateway springSecurityAdapter(PrincipalResolver principalResolver) {
        log.info("Using SpringSecurityAdapter for SecurityGateway");
        return new SpringSecurityAdapter(principalResolver);
    }

    // ---- SecurityGateway: Shiro adapter (fallback) ----
    @Bean
    @ConditionalOnClass(name = "org.apache.shiro.SecurityUtils")
    @ConditionalOnMissingBean(SecurityGateway.class)
    public SecurityGateway shiroAdapter(PrincipalResolver principalResolver) {
        log.info("Using ShiroAdapter for SecurityGateway");
        return new cn.watsontech.snapagent.boot2x.security.ShiroAdapter(principalResolver);
    }
}
