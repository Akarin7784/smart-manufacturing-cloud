# 编译阶段按 MODULE 构建目标服务及其依赖，只将可执行 JAR 带入运行镜像。
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
COPY cloud-api cloud-api
COPY cloud-common cloud-common
COPY cloud-gateway cloud-gateway
COPY device-service device-service
COPY workorder-service workorder-service
ARG MODULE
RUN mvn -B -ntp -pl ${MODULE} -am package -DskipTests && cp ${MODULE}/target/${MODULE}-1.0.0-SNAPSHOT.jar /app.jar
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app --create-home --home-dir /home/app app
COPY --from=build --chown=app:app /app.jar /app/app.jar
USER app
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
