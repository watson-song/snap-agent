package com.watsontech.snapagent.demo;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * DataSource for the snap-agent's read-only diagnostic queries.
 *
 * <p>All credentials are injected via environment variables — no hardcoded
 * defaults. The demo won't start unless {@code SNAP_AGENT_JDBC_URL},
 * {@code SNAP_AGENT_JDBC_USER}, and {@code SNAP_AGENT_JDBC_PASSWORD} are set.
 */
@Configuration
public class DataSourceConfig {

    @Value("${snap-agent.jdbc.url:}")
    private String jdbcUrl;

    @Value("${snap-agent.jdbc.username:}")
    private String username;

    @Value("${snap-agent.jdbc.password:}")
    private String password;

    @Bean
    public DataSource dataSource() {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(jdbcUrl);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setMaximumPoolSize(3);
        ds.setMinimumIdle(1);
        ds.setConnectionTimeout(15000);
        ds.setIdleTimeout(60000);
        ds.setMaxLifetime(300000);
        ds.setPoolName("snap-agent-jdbc");
        ds.setReadOnly(true);
        return ds;
    }
}
