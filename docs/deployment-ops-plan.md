# smart-manufacturing-cloud 部署与运维方案

> 基于 `main` 分支只读评估（2026-10-12）。技术栈：Spring Boot 3.5.0 / Spring Cloud 2025.0.0 / Spring Cloud Alibaba 2025.0.0.0 / Java 21（见 `pom.xml`）。
> 模块：`auth-service`、`cloud-gateway`、`device-service`、`workorder-service`，公共模块 `cloud-api`、`cloud-common`、`cloud-security`。

## 一、现状

| 领域 | 现有文件 | 现状 |
|---|---|---|
| 编排 | `compose.yaml`、`Dockerfile`、`.env.example` | 默认起 nacos v3.0.3、mysql 8.4、sentinel；`--profile app` 再起 auth/gateway/device/workorder。单一多阶段 `Dockerfile` 通过 `MODULE` 参数构建各服务，运行用户非 root。基础设施有 healthcheck，业务服务没有；gateway 依赖其它服务时只用 `service_started` |
| Nacos 配置 | `infra/nacos/{common,cloud-gateway,auth-service,device-service,workorder-service}.yml`、`scripts/import-nacos.ps1` | 配置组为 `MANUFACTURING`，各服务 `application-cloud.yml` 用 `optional:nacos:` 导入。导入只有 PowerShell 脚本，compose 不会自动导入；Nacos 鉴权关闭，`NACOS_AUTH_TOKEN` 直接写在 compose 里 |
| MySQL 初始化 | `infra/mysql/init.sql`、`infra/mysql/auth-init.sql`、`*/src/main/resources/schema.sql` | init.sql 只在数据卷首次初始化时执行，负责建库和账号；auth 库由一次性容器 `auth-db-init` 初始化。表结构和种子数据（如 D001/D002 设备）写在各服务的 `schema.sql` 里，并配置 `spring.sql.init.mode: always`，因此**没有版本化迁移** |
| Sentinel | `infra/sentinel/Dockerfile`（dashboard 1.8.9）；4 个服务依赖 `spring-cloud-starter-alibaba-sentinel`，gateway 另依赖 `sentinel-gateway` | 已接入 dashboard（`eager: true`）。**未发现规则持久化配置**（没有 Nacos datasource），规则在重启后丢失 |
| 监控 | 4 个服务都引入了 `spring-boot-starter-actuator` 和 `micrometer-registry-prometheus`；`application.yml` 暴露 `health,info,prometheus` 并开启健康探针 | 指标端点已就绪，但**没有 Prometheus/Grafana 部署，也没有看板** |
| 日志/追踪 | `cloud-common/.../RequestIdFilter.java`、`workorder-service/.../FeignConfiguration.java`；`logging.pattern.level` 中包含 `requestId` | 已有 requestId 透传和 MDC。**没有 JSON 结构化日志，也没有 OpenTelemetry/Micrometer Tracing** |
| CI | `.github/workflows/build.yml` | 在 ubuntu 和 windows 上执行 `mvnw verify`，用 `docker compose config` 校验编排，上传测试报告和 JAR；Action 已固定到 SHA，权限为只读。**不构建或发布镜像，没有 tag/release 流程** |
| 脚本 | `scripts/*.ps1`（start/stop-local、smoke-test、security-smoke-test、init-auth、import-nacos） | 只有 PowerShell 版本，Linux/macOS 使用不便 |
| 文档 | `docs/architecture.md`、`docs/authentication.md`、`docs/validation.md`、`README.md` | 尚无部署运维文档 |

## 二、差距

1. 没有真正的一条命令：需要先 `compose up`，再执行 PS 脚本导入 Nacos 并初始化 auth；演示数据只有两台设备。
2. 用 `schema.sql` + `mode: always` 管理表结构，没有版本记录，也无法安全升级表结构。
3. 业务服务没有 healthcheck，启动顺序依赖 `service_started`，并且没有 smoke 门禁。
4. Sentinel 规则不持久化。
5. 没有 Prometheus、Grafana 和告警。
6. 日志不是结构化格式；没有跨服务 trace（requestId 只能用于粗略关联）。
7. CI 不产出镜像，没有版本、SBOM、签名和漏洞扫描。

## 三、方案

### 1. 一条命令启动完整演示环境（含演示数据）
- 新增 `nacos-config-init` 一次性容器（curl 镜像，挂载 `infra/nacos/`，调用与 `import-nacos.ps1` 相同的 v3 运维接口），业务服务通过 `depends_on: service_completed_successfully` 等它完成。
- 为业务服务增加 healthcheck（探测 `/actuator/health/readiness`），gateway 改为依赖 `service_healthy`。**运行镜像不安装 curl/wget**：在镜像中放一个极小的 Java 探针（单文件 `HealthProbe.java`，用 JDK 自带 `java.net.http.HttpClient` 请求 readiness，非 200 时退出码为 1，通过 `java HealthProbe.java` 源码模式运行），compose 的 healthcheck 调用它。这样不增加攻击面，也不扩大镜像体积。
- 新增 `demo` profile 和 `Makefile` / `scripts/demo.sh` 跨平台入口：`make demo` = `docker compose --profile app --profile demo --profile observability up -d --build --wait`，完成后自动运行冒烟测试（参照 `scripts/smoke-test.ps1` 移植一个 sh 版本）。
- 演示数据放在 Flyway 的 `db/demo` 位置（见第 2 项），只在 `demo` 环境加载：设备约 20 台、多种状态，工单约 50 条，覆盖各个状态。
- **P1 即开启 Nacos 鉴权**（`NACOS_AUTH_ENABLE=true`），各服务和 `nacos-config-init` 使用账号密码访问 Nacos。
- 密钥统一放在**不入库的 `.env`**（`NACOS_AUTH_TOKEN`、Nacos 账号密码、MySQL 密码、`AUTH_BOOTSTRAP_PASSWORD` 等），从 compose 中删除明文；仓库只提交 `.env.example`（占位值和说明），并确认 `.gitignore` 包含 `.env`。`make demo` 在 `.env` 不存在时从 `.env.example` 生成并随机填充密钥，再把演示登录密码打印出来。
- `make demo-reset` = `down -v` 后重新执行 `demo`。
- **验收**：在干净机器上执行一条 `make demo`，10 分钟内所有容器 healthy；冒烟测试通过；登录后能查到演示设备和工单。

### 2. 用 Flyway 做数据库迁移
- device/workorder/auth 三个服务引入 `flyway-core` + `flyway-mysql`，删除 `schema.sql`，并把 `spring.sql.init.mode` 改为 `never`。
- 把现有 DDL 迁成 `db/migration/V1__baseline.sql`；为已有数据卷配置 `baseline-on-migrate: true`、`baseline-version: 1`。
- 种子/演示数据放在 `db/demo/R__demo_data.sql`（repeatable），只在 demo profile 中把它加入 `spring.flyway.locations`。**脚本必须幂等**：使用固定主键/业务编码，配合 `INSERT ... ON DUPLICATE KEY UPDATE`，不依赖自增 ID，不做无条件 DELETE。CI 中对同一个库连续执行两次迁移（第二次需修改校验和触发重跑），并校验两次后的行数和关键数据一致。
- `infra/mysql/*.sql` 保持只负责建库和账号；把 `auth-init.sql` 合入 init 流程。
- Testcontainers MySQL 迁移测试（先 migrate 再 validate）**由 MES测试与质量负责**编写和维护，纳入其测试分层；运维这边负责提供迁移脚本，并在 CI 中接入该 job。
- **验收**：空库和旧数据卷都能启动；`flyway_schema_history` 中有记录；修改已发布的迁移脚本会让 CI 失败。

### 3. Prometheus / Grafana 看板
- 新增 `infra/observability/`：`prometheus.yml`（抓取 4 个服务的 `/actuator/prometheus`，可通过 Nacos 服务发现或静态 compose DNS）、Grafana 的 provisioning（数据源 + 看板 JSON），放在 compose 的 `observability` profile 中。
- 看板：JVM/HTTP（RED 指标）/HikariCP/Gateway 路由/Sentinel QPS 与 block 数，再加一个业务看板（设备在线率、工单状态分布，需要补自定义 Micrometer 指标）。
- 告警规则示例：服务 down、5xx 比例、p95 延迟、连接池耗尽。
- 同时完成 Sentinel 规则持久化：引入 `sentinel-datasource-nacos`，把规则存到 `infra/nacos/*-flow-rules.json` 并随配置导入。
- **验收**：Grafana 打开后自动出现看板；压测时能看到 QPS/延迟变化，触发限流后 block 指标变化；Prometheus targets 全部 UP。

### 4. 结构化日志与链路追踪
- 使用 Spring Boot 3.4+ 原生的 `logging.structured.format.console: ecs`（或 logstash），通过 `cloud`/`demo` profile 开启，本地开发仍用文本格式。
- 引入 `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp`，Feign 和 Gateway 会自动传播 traceparent；日志 MDC 中加入 traceId/spanId；`RequestIdFilter` 保留并与 traceId 关联。
- compose `observability` profile 增加 OTel Collector + Tempo（或 Jaeger），日志可选 Loki + Promtail，并在 Grafana 中打通 日志 ↔ trace ↔ 指标（exemplars）。
- 采样率：demo 为 1.0，生产通过配置调整。
- **验收**：一次网关请求能在 Tempo/Jaeger 中看到 gateway→workorder→device 的完整链路；日志是 JSON 且包含 traceId；在 Grafana 中能从日志跳转到 trace。

### 5. 镜像构建与发布流水线（GitHub Actions）
- 保留 `build.yml`。新增 `release.yml`：在 `v*` tag 和 main 分支 push 时触发，用 matrix 构建 4 个服务（复用根 `Dockerfile` 的 `MODULE` 参数），使用 buildx 与 GHA 缓存，推送到 GHCR（`ghcr.io/akarin7784/<service>`），标签为 `sha`、`semver`、`latest(main)`。
- 只给需要的 job 授予 `packages: write`、`id-token: write`；增加 Trivy 扫描（**只有 CRITICAL 让构建失败**，HIGH 只告警并在 job summary 中列出，`--ignore-unfixed`）、SBOM（syft）和 cosign keyless 签名；继续固定 Action 的 SHA，保持现有做法。
- PR 上只构建、不推送镜像，并执行一次 compose 冒烟（`--wait` + smoke 脚本）。
- tag 时生成 GitHub Release（changelog + `compose.release.yaml`，其中改用镜像而非 build）。
- 处理 `Dockerfile` 中写死的版本号 `1.0.0-SNAPSHOT`：改为通配或通过 `-Drevision` 传入版本。
- **验收**：推送 `v1.0.0` 后 GHCR 上有 4 个带签名的镜像，Release 中附带 compose 文件；PR 冒烟失败时阻止合并。

## 四、分阶段计划

| 阶段 | 内容 | 预估 |
|---|---|---|
| P1 | 一条命令启动（Nacos 自动导入、healthcheck、Makefile/sh 脚本、smoke.sh） | 2–3 天 |
| P2 | Flyway 迁移 + 演示数据 + Testcontainers | 2–3 天 |
| P3 | Prometheus/Grafana + Sentinel 规则持久化 | 2–3 天 |
| P4 | 结构化日志 + OTel 追踪 | 2 天 |
| P5 | 镜像发布流水线 + 扫描与签名 + Release | 2 天 |

每个阶段单独提 PR，CI 绿灯并通过冒烟测试后再合并。

## 五、风险
- Nacos 3 运维接口与 2.x 不兼容：导入容器需要固定使用 v3 路径。
- 已有数据卷第一次切换到 Flyway 时需要 baseline；先在 demo-reset 环境验证。
- windows CI 不能跑 Linux 容器：容器相关任务只放在 ubuntu。
- 密钥只放在不入库的 `.env`；生产环境改用平台 secrets（如 K8s Secret），Nacos 鉴权在所有环境保持开启。
