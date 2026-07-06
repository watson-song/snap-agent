package com.watsontech.snapagent.demo;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;

/**
 * Demo-only Spring Security config.
 *
 * <ul>
 *   <li>Public: /snap-agent/user-info — returns auth status (no login required).</li>
 *   <li>Public: static resources (.html, .js, .css).</li>
 *   <li>Public: SSE stream endpoint (/snap-agent/runs/{id}/stream) — EventSource sends
 *       cookies automatically; controller verifies task ownership.</li>
 *   <li>Public: /snap-agent-internal/ — permitAll; the shared secret in the
 *       InternalTaskController is the only credential (host must permit this path
 *       for pod-to-pod traffic).</li>
 *   <li>All other /snap-agent/ endpoints — basic auth (user demo/demo),
 *       so the SecurityGateway resolves a real principal.</li>
 * </ul>
 */
@Configuration
public class SecurityConfig extends WebSecurityConfigurerAdapter {

    @Override
    protected void configure(HttpSecurity http) throws Exception {
        http.csrf().disable()
                .authorizeRequests()
                // Public: user-info endpoint (returns auth status, no login required)
                .antMatchers("/snap-agent/user-info").permitAll()
                // Public: static resources (SPA shell)
                .antMatchers("/snap-agent/*.html", "/snap-agent/*.js",
                        "/snap-agent/*.css").permitAll()
                // Public: SSE stream (EventSource sends cookies automatically;
                // controller verifies task ownership)
                .antMatchers("/snap-agent/runs/*/stream").permitAll()
                // Public: internal pod-to-pod endpoint (uses shared secret)
                .antMatchers("/snap-agent-internal/**").permitAll()
                // All other SnapAgent API endpoints require authentication
                .antMatchers("/snap-agent/**").authenticated()
                .anyRequest().authenticated()
                .and()
                .httpBasic();
    }

    @Override
    protected void configure(AuthenticationManagerBuilder auth) throws Exception {
        auth.inMemoryAuthentication()
                .withUser("demo").password("{noop}demo")
                .authorities("snap-agent:access");
    }
}
