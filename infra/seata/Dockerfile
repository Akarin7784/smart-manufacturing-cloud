# 官方镜像不附带 MySQL 驱动，构建时从 Maven Central 获取与业务 BOM 一致的版本。
FROM maven:3.9.9-eclipse-temurin-21 AS driver
RUN --mount=type=cache,target=/root/.m2,sharing=locked mvn -B -ntp dependency:get -Dartifact=com.mysql:mysql-connector-j:9.2.0 -Dtransitive=false && cp /root/.m2/repository/com/mysql/mysql-connector-j/9.2.0/mysql-connector-j-9.2.0.jar /driver.jar
FROM apache/seata-server:2.5.0
# Seata 将 MySQL 驱动从 loader.path 下 jdbc 子目录隔离加载。
COPY --from=driver /driver.jar /seata-server/libs/jdbc/mysql-connector-j-9.2.0.jar
ENV LOADER_PATH=/seata-server/libs
