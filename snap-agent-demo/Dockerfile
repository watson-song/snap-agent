FROM openjdk:8-jre-alpine

WORKDIR /app

# Copy the fat jar
COPY target/snap-agent-demo-1.0.0-SNAPSHOT.jar /app/app.jar

# Copy skill files to the filesystem (SkillRegistry reads from disk, not classpath)
COPY src/main/resources/skills /app/skills

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
