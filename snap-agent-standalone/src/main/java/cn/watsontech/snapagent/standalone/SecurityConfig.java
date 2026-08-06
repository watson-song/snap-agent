package cn.watsontech.snapagent.standalone;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;

/**
 * Spring Security configuration for standalone mode.
 *
 * <p>Configures:</p>
 * <ul>
 *   <li>JWT authentication filter</li>
 *   <li>CORS support (configurable allowed origins)</li>
 *   <li>Stateless session (no server-side session)</li>
 *   <li>SnapAgent endpoints: permitAll (auth handled by JWT filter + snap-agent internally)</li>
 *   <li>Actuator health: permitAll</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${snap-agent.security.jwt.secret:snap-agent-default-secret-change-me}")
    private String jwtSecret;

    @Value("${snap-agent.security.jwt.user-claim:sub}")
    private String userClaim;

    @Value("${snap-agent.standalone.cors-allowed-origins:*}")
    private String corsAllowedOrigins;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors().and()
            .csrf().disable()
            .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            .and()
            .authorizeRequests()
                // Actuator health
                .antMatchers("/actuator/health", "/actuator/info").permitAll()
                // SnapAgent UI (static resources)
                .antMatchers("/snap-agent/*.html", "/snap-agent/*.js",
                             "/snap-agent/*.css", "/snap-agent/*.svg",
                             "/snap-agent/*.png").permitAll()
                // SnapAgent API (auth handled by JWT filter + snap-agent internally)
                .antMatchers("/snap-agent/**").permitAll()
                // Everything else: authenticated
                .anyRequest().authenticated()
            .and()
            .addFilterBefore(jwtAuthenticationFilter(), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter() {
        return new JwtAuthenticationFilter(jwtSecret, userClaim);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        if ("*".equals(corsAllowedOrigins)) {
            config.addAllowedOrigin("*");
        } else {
            Arrays.stream(corsAllowedOrigins.split(","))
                    .map(String::trim)
                    .forEach(config::addAllowedOrigin);
        }
        config.addAllowedHeader("*");
        config.addAllowedMethod("*");
        config.setAllowCredentials(false);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
