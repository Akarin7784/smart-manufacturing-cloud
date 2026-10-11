# 编译阶段按 MODULE 构建目标服务及其依赖，只将可执行 JAR 带入运行镜像。
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
COPY cloud-api cloud-api
COPY cloud-security cloud-security
COPY auth-service auth-service
COPY cloud-common cloud-common
COPY cloud-gateway cloud-gateway
COPY device-service device-service
COPY workorder-service workorder-service
ARG MODULE
RUN --mount=type=cache,target=/root/.m2,sharing=locked mvn -B -ntp -pl ${MODULE} -am package -DskipTests && cp ${MODULE}/target/${MODULE}-1.0.0-SNAPSHOT.jar /app.jar
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app --create-home --home-dir /home/app app && mkdir -p /home/app/security && chown app:app /home/app/security
COPY --from=build --chown=app:app /app.jar /app/app.jar
USER app
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
