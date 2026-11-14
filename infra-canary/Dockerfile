# infra-canary image (Docker build) from the jar Maven already built. Equivalent to the Jib build in pom.xml.
#   mvn -B package                                   -> target/infra-canary-0.2.0.jar
#   docker build -t 192.168.1.173/infracanary/infra-canary:0.2.0 .
#   docker push  192.168.1.173/infracanary/infra-canary:0.2.0      (registry setup: tool-box/harbour/runbook.md)
# Another version: docker build --build-arg JAR_FILE=target/infra-canary-<version>.jar ...
FROM eclipse-temurin:17-jre

ARG JAR_FILE=target/infra-canary-0.2.0.jar

# Non-root. The log volume is mounted at /app/logs/canary; the Deployment's fsGroup (10001) makes it
# writable for this user. Without a volume (docker run) the directory below is used.
RUN groupadd --gid 10001 canary && useradd --uid 10001 --gid canary --no-create-home --shell /usr/sbin/nologin canary \
 && mkdir -p /app/logs/canary && chown -R canary:canary /app
WORKDIR /app
COPY --chown=canary:canary ${JAR_FILE} /app/app.jar
USER 10001:10001
ENV LOGGING_FILE_NAME=/app/logs/canary/infra-canary.log
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
