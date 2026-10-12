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
# 健康探针在构建阶段用 javac 编译：运行镜像只有 JRE，无法使用源码模式直接运行 .java 文件。
COPY infra/healthcheck/HealthProbe.java /probe/HealthProbe.java
RUN javac -d /probe /probe/HealthProbe.java
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app --create-home --home-dir /home/app app && mkdir -p /home/app/security && chown app:app /home/app/security
COPY --from=build --chown=app:app /app.jar /app/app.jar
# 只复制编译后的探针类，运行镜像不安装 curl/wget；compose 中的 healthcheck 调用它。
COPY --from=build --chown=app:app /probe/HealthProbe.class /app/probe/HealthProbe.class
USER app
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
