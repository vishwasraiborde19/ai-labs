# Build the jar first: mvn -q package
FROM eclipse-temurin:21-jre
RUN useradd --uid 10001 --no-create-home --shell /usr/sbin/nologin agent
COPY target/mcp-kube-agent.jar /opt/mcp-kube-agent.jar
USER 10001
EXPOSE 8767
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/opt/mcp-kube-agent.jar"]
CMD ["--spring.profiles.active=http"]
