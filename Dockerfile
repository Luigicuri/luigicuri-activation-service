# syntax=docker/dockerfile:1

# ---- Build: compile and package with the Maven wrapper ----------------------------------------
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

# Dependencies first, so this layer is cached until pom.xml changes
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

COPY src/ src/
# Tests run in CI (./mvnw verify); they need Docker for Testcontainers, which is not available here
RUN ./mvnw -B -q package -DskipTests \
    && java -Djarmode=tools -jar target/activation-service-*.jar extract --layers --launcher --destination extracted

# ---- Runtime: JRE only, non-root, layered so code changes do not re-ship dependencies ---------
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app

COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./

USER app
EXPOSE 8080 8081

# Heap sized from the container memory limit, not the host's; fail fast on OOM so the orchestrator restarts it
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError" \
    SPRING_PROFILES_ACTIVE=docker

HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=3 \
    CMD wget -qO- http://localhost:8081/actuator/health/liveness || exit 1

# exec form via sh so JAVA_OPTS is expanded while java still receives SIGTERM (graceful shutdown)
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
