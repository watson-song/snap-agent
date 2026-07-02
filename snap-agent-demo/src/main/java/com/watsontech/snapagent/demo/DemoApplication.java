package com.watsontech.snapagent.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Minimal host application that embeds the snap-agent starter.
 *
 * <p>Used by the Docker E2E test to verify the starter auto-configures correctly
 * inside a real Spring Boot 2.x app: controller mounted, filter registered,
 * internal endpoints reachable, skills loaded from the classpath.</p>
 */
@SpringBootApplication
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
