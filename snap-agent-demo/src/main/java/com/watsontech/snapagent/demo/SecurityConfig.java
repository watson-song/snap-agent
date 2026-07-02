package com.watsontech.snapagent.demo;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;

/**
 * Demo-only Spring Security config.
 *
 * <ul>
 *   <li>Public read endpoints ({@code /snap-agent/skills}, {@code /models},
 *       {@code /tools}, {@code /skills/refresh}) — no auth, for easy curl-ing.</li>
 *   <li>{@code /snap-agent/runs/**} — basic auth (user {@code demo}/{@code demo}),
 *       so the {@code SecurityGateway} resolves a real principal.</li>
 *   <li>{@code /snap-agent-internal/**} — permitAll; the shared secret in the
 *       {@link com.watsontech.snapagent.boot2x.web.InternalTaskController} is the only
 *       credential (host must permit this path for pod-to-pod traffic).</li>
 * </ul>
 */
@Configuration
public class SecurityConfig extends WebSecurityConfigurerAdapter {

    @Override
    protected void configure(HttpSecurity http) throws Exception {
        http.csrf().disable()
                .authorizeRequests()
                .antMatchers("/snap-agent/skills", "/snap-agent/models",
                        "/snap-agent/tools", "/snap-agent/skills/refresh",
                        "/snap-agent/skills/upload",
                        "/snap-agent/skills/upload-folder").permitAll()
                .antMatchers("/snap-agent/*.html", "/snap-agent/*.js",
                        "/snap-agent/*.css").permitAll()
                .antMatchers("/snap-agent/runs/*/stream").permitAll()
                .antMatchers("/snap-agent-internal/**").permitAll()
                .antMatchers("/snap-agent/runs/**").authenticated()
                .anyRequest().authenticated()
                .and()
                .httpBasic();
    }

    @Override
    protected void configure(AuthenticationManagerBuilder auth) throws Exception {
        auth.inMemoryAuthentication()
                .withUser("demo").password("{noop}demo").roles("USER");
    }
}
