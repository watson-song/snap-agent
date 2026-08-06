package cn.watsontech.snapagent.standalone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * SnapAgent Standalone — production-ready independent deployment.
 *
 * <p>Runs as a standalone Spring Boot application with its own JWT authentication,
 * DataSource, and all snap-agent features. No host application required.</p>
 *
 * <p>Usage:</p>
 * <pre>
 * java -jar snap-agent-standalone.jar \
 *   --snap-agent.security.jwt.secret=your-secret \
 *   --spring.datasource.url=jdbc:mysql://host:3306/db
 * </pre>
 */
@SpringBootApplication
public class StandaloneApplication {
    public static void main(String[] args) {
        SpringApplication.run(StandaloneApplication.class, args);
    }
}
