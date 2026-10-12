# 可观测性（P3 监控）

本文说明指标暴露方式、可选监控栈（Prometheus + Grafana）、告警规则和验证方法。

## 1. 指标暴露

| 服务 | 业务端口 | 管理端口（cloud profile） |
| --- | --- | --- |
| cloud-gateway | 8080（发布到宿主机） | 18080 |
| device-service | 8081 | 18081 |
| workorder-service | 8082 | 18082 |
| auth-service | 8083 | 18083 |
| masterdata-service | 8084 | 18084 |

- 各服务已有 `spring-boot-starter-actuator` 和 `micrometer-registry-prometheus`，暴露 `health,info,prometheus`。
- **所有指标统一带 `application` 标签**（`management.metrics.tags.application=${spring.application.name}`），并为 `http.server.requests` 开启直方图桶，用于计算 P95/P99。
- **只对内网开放**：cloud profile（compose）下 actuator 迁到独立管理端口 `1808x`（可用 `MANAGEMENT_PORT` 覆盖），该端口**不映射到宿主机**，只在 compose 网络内可达。
  - 管理端口上只匿名放开 `/actuator/prometheus` 和健康检查；`/actuator/info` 等其他端点仍需 `metrics:read`。
  - 判定依据是请求实际到达的本地套接字端口（`ManagementPortAccess`），不是 `Host` 头，客户端无法伪造。
  - 业务端口（包括网关 8080）上 `/actuator/**` 仍要求 `metrics:read`，匿名访问返回 401。网关路由只转发 `/api/**`，因此也无法经网关访问下游的 actuator。
  - 网关在 8080 额外提供 `/livez`、`/readyz`，宿主机仍可确认网关存活。
- local profile（本机 IDE 开发）不分离端口，行为和以前一样：`/actuator/prometheus` 需要带 `metrics:read` 的令牌。
- compose 健康检查（`HealthProbe`）改为探测管理端口上的 `/actuator/health/readiness`。

## 2. 可选监控栈（compose `monitoring` profile）

默认的 `app` profile **不会**启动监控容器：整套 demo 已占用约 4.6G 内存。需要时叠加 profile：

```sh
make demo                                                   # 或已在运行的 app 栈
docker compose --profile app --profile monitoring up -d --wait prometheus grafana
sh scripts/monitoring-smoke.sh                              # 可选：检查 targets 全部 up
```

| 组件 | 地址（只绑定 127.0.0.1） | 内存上限 | 说明 |
| --- | --- | --- | --- |
| Prometheus v3.5.0 | http://localhost:9090 | 384m | 数据保留 2 天或 512MB |
| Grafana 12.1.1 | http://localhost:3000 | 256m | 管理员账号见 `.env` |

- **Grafana 管理员**：`GRAFANA_ADMIN_USER` 和 `GRAFANA_ADMIN_PASSWORD` 都由 `scripts/init-env.sh`（或 `init-env.ps1`）随机生成并写入 `.env`。compose 中这两个变量是必填的，缺失时直接报错，不会回退到 `admin/admin`。同时关闭了自助注册、匿名访问和外网统计上报。
- 清理：`docker compose --profile app --profile monitoring down -v --rmi local`（`-v` 会一并删除 `prometheus-data`、`grafana-data`）。

### 抓取方式：静态配置，不用 Nacos 服务发现

配置文件是 `infra/observability/prometheus/prometheus.yml`，按 compose 服务名静态抓取 5 个管理端口。不用 Nacos 发现的原因：

1. Prometheus 没有内置 Nacos SD，接入需要额外部署 adapter 或 http_sd 桥接服务；
2. Nacos 注册的是业务端口，而指标在管理端口，仍要重写端口；
3. 演示环境服务清单固定，静态配置更直观，冒烟脚本也能据此断言 target 数量。

新增服务时，在 `targets` 中加一行，并同步 `scripts/monitoring-smoke.sh` 的 `EXPECTED_TARGETS`。

### Grafana 自动配置

- 数据源：`infra/observability/grafana/provisioning/datasources/prometheus.yml`，uid 固定为 `prometheus`，只读。
- 看板：`infra/observability/grafana/dashboards/smc-overview.json`（uid `smc-overview`，位于 “Smart Manufacturing” 文件夹）。
  - JSON 由 `infra/observability/grafana/generate-dashboard.py` 生成，**请改脚本再重新生成**，不要手改 JSON；CI 的 `observability-lint` 会检查二者是否一致。
  - 面板分组：服务存活；HTTP 请求速率、5xx 比例、P95 延迟、4xx 按状态码；JVM 堆使用率、GC 暂停、线程、Hikari 连接池；关键业务接口。
- **关于业务指标**：仓库目前**没有自定义业务计数器或计时器**（Micrometer `Counter`/`Timer`）。业务面板复用 Spring 自动埋点的 `http_server_requests`，按 URI 模板筛选：工单创建 `POST /workorders`、下达 `POST /workorders/{id}/release`（速率按状态码和 P95）、登录 `/auth/login` 和服务令牌 `/auth/oauth2/token`（按状态码）、BOM 和工艺路线发布。报工、库存预留等领域加上专用指标后（例如 `mes.workorder.report`），再补充对应面板。

## 3. 告警规则

文件：`infra/observability/prometheus/alerts.yml`。演示环境不部署 Alertmanager，告警在 Prometheus 的 Alerts 页面查看。

| 告警 | 条件 | 持续 | 级别 |
| --- | --- | --- | --- |
| `ServiceDown` | `up{job="spring-boot"} == 0` | 1m | critical |
| `HighHttp5xxRatio` | 按 application 统计 5 分钟内 5xx 占比 > 5%，且请求量 > 0.1 req/s（避免低流量下误报） | 5m | warning |
| `JvmHeapUsageHigh` | 已用堆 / 最大堆 > 90%（只统计 max > 0 的内存池） | 5m | warning |
| `OutboxBacklog` | `max by (application) ({__name__=~"mes_.*_outbox_pending"}) > 100` | 5m | warning |

`OutboxBacklog` 依赖第 5 节约定的 `mes_*_outbox_pending` Gauge。**这个指标目前还不存在**，选择器匹配不到任何序列，所以规则已加载但不会触发；工单和库存服务实现该指标后自动生效，不需要再改规则。

阈值按演示规模（单实例、低流量）设定，生产环境需要按 SLO 重新评估。

## 4. 验证

- 单元测试：`ManagementPortMetricsTest`（device-service，代表所有 Servlet 服务）和 `GatewayManagementPortTest`（网关，WebFlux）覆盖三点：
  - 管理端口可以匿名抓取指标，且指标带 `application` 标签；
  - 管理端口上的其他端点仍需权限；
  - 业务端口返回 401。
- `scripts/monitoring-smoke.sh`：只在 monitoring profile 下运行。检查以下各项：
  - Prometheus 的 `spring-boot` targets 全部 up（轮询等待，默认 120 秒）；
  - 4 条告警规则都已加载；
  - 指标带 `application` 标签；
  - Grafana 健康、数据源连通、看板已加载；
  - 网关 8080 的 `/actuator/prometheus` 返回 401。
- CI：
  - `verify` 校验 app profile 不含监控容器，并校验 monitoring 叠加后的 compose 配置；
  - `observability-lint` 用 promtool 检查抓取配置和 4 条告警规则，并确认看板 JSON 与生成脚本一致；
  - `demo smoke` 在 `make demo` 之后额外拉起 prometheus 和 grafana，运行 `monitoring-smoke.sh`（约增加 1–2 分钟）。这里不单独开 job，因为单独的 job 需要重新构建并启动整套服务，耗时 10 分钟以上。

## 5. 业务指标命名约定

以下指标**尚未实现**：工单指标在报工 PR 里实现，库存指标在库存服务的实现 PR 里实现。本节只约定名称和标签，实现时以此为准。

通用规则：
- 名称用 `mes_<领域>_<对象>_<度量>`，下面写的是 Prometheus 导出后的名字。Micrometer 里用点号命名，例如 `mes.workorder.report`，Counter 导出时自动加 `_total`。
- **标签里不放工单号、预留单号、批次号等业务标识**：它们的取值无限增长，会导致时间序列基数爆炸。需要按单号追踪时查日志（带 requestId）或业务表。
- `result` 标签与该服务固定的 Problem type 枚举**一一对应**，把连字符改成下划线，例如 `over-report` 写成 `over_report`。另外加上成功 `ok`，以及兜底的 `error`（未映射的异常和 5xx）。枚举新增值时同步新增标签值。
- 数量类指标必须带 `uom` 标签，**单位不同的数量不能直接相加**，面板和查询都要按 `uom` 分组。
- 面板等指标合入后再补，届时更新 `generate-dashboard.py`。

### 工单服务（workorder-service）

| 指标 | 类型 | 标签 | 说明 |
| --- | --- | --- | --- |
| `mes_workorder_report_total` | Counter | `result` | 报工请求结果。`result` 的取值是 `ok`，加上工单服务 Problem type 的下划线写法（按已拍板的设计为 `over_report`、`downstream_reported`、`workorder_closed`、`operations_unreported`，最终以工单服务的固定枚举为准），再加 `error` |
| `mes_workorder_report_qty` | Counter | `kind`（`good`/`defect`/`scrap`）、`uom` | 报工数量累计（冲销记负数，或另计到冲销指标里，实现时在 PR 中说明） |
| `mes_workorder_reversal_total` | Counter | — | 冲销笔数 |
| `mes_workorder_status_transition_total` | Counter | `to`（目标状态，如 `IN_PROGRESS`、`COMPLETED`） | 工单状态迁移次数 |
| `mes_workorder_report_lock_wait` | Timer | — | 报工和冲销事务里 `SELECT … FOR UPDATE` 锁工单行的等待时间 |
| `mes_workorder_outbox_pending` | Gauge | — | 待投递的 outbox 事件数（对应告警 `OutboxBacklog`） |

### 库存服务（inventory-service）

| 指标 | 类型 | 标签 | 说明 |
| --- | --- | --- | --- |
| `mes_inventory_reservation_total` | Counter | `result` | 预留相关请求结果。`result` 的取值是 `ok`、`replay`（幂等重放），8 种 Problem type 的下划线写法（`insufficient_stock`、`over_consume`、`idempotency_key_conflict`、`reservation_expired`、`reservation_released`、`reservation_consumed`、`line_closed`、`validation_error`，见 `docs/api/inventory-openapi.yaml` 的 `ProblemType`），再加 `error` |
| `mes_inventory_reservation_transition_total` | Counter | `to`（`RELEASED`/`EXPIRED`/`CONSUMED` 等） | 预留单状态迁移次数 |
| `mes_inventory_movement_qty` | Counter | `kind`（出入库类型）、`uom` | 出入库数量累计，不同 `uom` 不能相加 |
| `mes_inventory_outbox_pending` | Gauge | — | 待投递的 outbox 事件数（对应告警 `OutboxBacklog`） |
| `mes_inventory_stock_lock_wait` | Timer | — | 扣减或预留时锁库存行的等待时间 |


### 质检服务（quality-service）

契约见 `docs/api/quality-openapi.yaml`，设计见 `docs/quality-design.md`。

| 指标 | 类型 | 标签 | 说明 |
| --- | --- | --- | --- |
| `mes_quality_api_total` | Counter | `result` | 修改类接口结果。`result` 的取值是 `ok`、`replay`（幂等重放），6 种 Problem type 的下划线写法（`validation_error`、`idempotency_key_conflict`、`uom_mismatch`、`over_dispose`、`invalid_status_transition`、`ncr_voided`，见 `quality-openapi.yaml` 的 `ProblemType`），再加 `error` |
| `mes_quality_ncr_transition_total` | Counter | `to`（`OPEN`/`UNDER_REVIEW`/`DISPOSED`/`CLOSED`/`VOIDED`） | NCR 状态迁移次数，事务提交后计数 |
| `mes_quality_disposition_qty` | Counter | `type`（`REWORK`/`SCRAP`/`CONCESSION`）、`uom` | 处置数量累计，不同 `uom` 不能相加 |
| `mes_quality_event_consumed_total` | Counter | `tag`、`result`（`ok`/`duplicate`/`pending`/`matched`/`disposed_reversal_conflict`/`error`） | 消费 `mes-workorder-quality` 的结果；`disposed_reversal_conflict` 即已处置 NCR 被冲销（不对应 HTTP Problem type） |
| `mes_quality_pending_reversal_expired_total` | Counter | — | 暂存冲销超过 TTL（默认 24h）未对冲的次数 |
| `mes_quality_outbox_pending` | Gauge | — | 待投递的 outbox 事件数（自动匹配告警 `OutboxBacklog`） |
| `mes_quality_ncr_open` | Gauge | — | 未关闭（OPEN/UNDER_REVIEW/DISPOSED）的 NCR 数 |

待质检服务实现后由运维新增告警：`QualityDisposedReversalConflict`（`disposed_reversal_conflict` 增量 > 0 即告警）、`QualityReversalOrphan`（`pending_reversal_expired_total` 增量 > 0）。
