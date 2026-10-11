# 固定 Sentinel 控制台版本，确保与项目依赖版本一致。
FROM eclipse-temurin:21-jre
WORKDIR /app
ADD https://github.com/alibaba/Sentinel/releases/download/1.8.9/sentinel-dashboard-1.8.9.jar /app/sentinel-dashboard.jar
ENV JAVA_TOOL_OPTIONS="-Dserver.port=8858 -Dcsp.sentinel.dashboard.server=localhost:8858 -Dproject.name=sentinel-dashboard"
EXPOSE 8858
ENTRYPOINT ["java", "-jar", "/app/sentinel-dashboard.jar"]
