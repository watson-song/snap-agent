# ---- Build stage ----
FROM maven:3.8-openjdk-8 AS builder

WORKDIR /build
COPY pom.xml .
COPY snap-agent-core/pom.xml snap-agent-core/
COPY snap-agent-spring-boot-2x-starter/pom.xml snap-agent-spring-boot-2x-starter/
COPY snap-agent-client/pom.xml snap-agent-client/
COPY snap-agent-standalone/pom.xml snap-agent-standalone/

# Download dependencies (cached layer)
RUN mvn dependency:go-offline -pl snap-agent-standalone -am -q 2>/dev/null || true

# Copy source and build
COPY snap-agent-core/ snap-agent-core/
COPY snap-agent-spring-boot-2x-starter/ snap-agent-spring-boot-2x-starter/
COPY snap-agent-client/ snap-agent-client/
COPY snap-agent-standalone/ snap-agent-standalone/

RUN mvn clean package -DskipTests -pl snap-agent-standalone -am -q

# ---- Runtime stage ----
FROM openjdk:8-jre-slim

LABEL maintainer="snap-agent"
LABEL description="SnapAgent Standalone — AI Agent for Spring Boot projects"

RUN groupadd -r snapagent && useradd -r -g snapagent snapagent

WORKDIR /app

COPY --from=builder /build/snap-agent-standalone/target/snap-agent-standalone.jar app.jar

# Data directories
RUN mkdir -p /app/data/logs /app/data/skills /app/data/conversations /app/data/code-graph \
    && chown -R snapagent:snapagent /app

USER snapagent

EXPOSE 8090

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD curl -f http://localhost:8090/actuator/health || exit 1

ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "app.jar"]
