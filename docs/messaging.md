# 消息中间件（RocketMQ）

演示环境用 RocketMQ 5.5.0（`apache/rocketmq` 官方镜像，remoting 协议）承载领域事件，随 `make demo`（compose `app` profile）启动。

## 可靠性说明（重要）

- **演示环境消息不持久化**：Broker 不挂数据卷（镜像以非 root 运行，命名卷会归 root 导致无法写入），`down`、重建或删除容器后消息全部丢失。
- **业务可靠性靠 outbox 重发，而不是 MQ 持久化**：事件与业务数据在同一本地事务写入 outbox 表，发布任务投递成功后才标记已发送；失败、超时或 Broker 重建导致消息丢失时，从 outbox 重新发布。消费者按 eventId 幂等处理，重复投递无副作用。
- 生产环境仍应为 Broker 配置持久化存储、同步刷盘或主从，但这只是降低丢失概率，不替代 outbox。

## 组件

| 容器 | 作用 | 健康检查 | 资源 |
| --- | --- | --- | --- |
| `namesrv` | NameServer，宿主机仅绑定 `127.0.0.1:9876` | `/dev/tcp` 探测 9876 | 堆 ≤256M |
| `broker` | 单主 Broker，配置见 `infra/rocketmq/broker.conf`，不对宿主机暴露端口 | `mqadmin clusterList` 确认 broker-a 已注册到 NameServer（探测 JVM 堆 64M，间隔 30s，启动期每 5s） | 堆 ≤512M，直接内存 ≤256M，CommitLog 单文件 128M |
| `rocketmq-init` | 一次性容器：确认 Broker 已注册后按 `infra/rocketmq/topics.txt` 幂等建 Topic | 成功退出 | 工具堆 128M |

- Broker 关闭了自动建 Topic，新 Topic 必须登记到 `infra/rocketmq/topics.txt`。
- JVM 参数可在 `.env` 用 `ROCKETMQ_NAMESRV_JAVA_OPT` / `ROCKETMQ_BROKER_JAVA_OPT` 覆盖；共享机器上不要调大 Broker 堆。
- 演示环境未开启 ACL，因此没有密钥；生产环境需开启 ACL 并通过平台 secrets 注入。

## 共享配置（Nacos `MANUFACTURING/common.yml`）

| 键 | 值 | 说明 |
| --- | --- | --- |
| `rocketmq.name-server` | `${ROCKETMQ_NAMESRV_ADDR:localhost:9876}` | compose 统一注入 `namesrv:9876` |
| `manufacturing.mq.topics.inventory-reservation` | `mes-inventory-reservation` | 库存预留事件，库存服务经 outbox 发布 |
| `manufacturing.mq.topics.workorder-event` | `mes-workorder-event` | 工单状态事件（Tag：`WorkOrderStarted` / `WorkOrderCompleted`） |
| `manufacturing.mq.topics.workorder-quality` | `mes-workorder-quality` | 工单不良品待质检事件，工单服务经 outbox 发布 |
| `manufacturing.mq.tags.*` | `ReservationCreated` / `ReservationReleased` / `ReservationConsumed` / `ReservationExpired`；`WorkOrderStarted` / `WorkOrderCompleted`；`DefectReported` / `DefectReversed` | 同一 Topic 按 Tag 区分事件类型 |
| `manufacturing.mq.consumer-groups.workorder-reservation` | `CG_WORKORDER_RESERVATION` | 工单服务订阅预留事件 |
| `manufacturing.mq.consumer-groups.quality-defect` | `CG_QUALITY_DEFECT` | 质检服务订阅不良品事件 |

`ReservationExpired` 由库存服务定时任务判定后发布，工单服务只订阅、不自行计算过期。服务代码通过 `@ConfigurationProperties("manufacturing.mq")` 引用，禁止硬编码 Topic。

## 工单状态事件（`mes-workorder-event`）

工单服务经 outbox 向本 Topic 发布工单生命周期状态事件。

| 项 | 值 |
| --- | --- |
| Topic | `mes-workorder-event` |
| Tag | `WorkOrderStarted`（首笔报工，状态转 `IN_PROGRESS`）、`WorkOrderCompleted`（状态转 `COMPLETED`）、`WorkOrderReopened`（冲销使 `COMPLETED` 退回 `IN_PROGRESS`）；生命周期 `WorkOrderReleased`、`WorkOrderHeld`、`WorkOrderResumed`、`WorkOrderHoldReasonCleared`、`WorkOrderCancelled`、`WorkOrderClosed`、`WorkOrderReleaseWithdrawn`（PR2） |
| 生产者 | workorder-service（业务数据与 outbox 同事务写入，由 outbox 中继投递，见下文“工单服务 outbox 中继”） |
| 幂等 | 消费端按 `eventId` 去重，重复投递无副作用 |

`WorkOrderCompleted` 在末道工序累计合格数达到计划数时自动发出（`completionMode=AUTO`），也可经 `POST /api/workorders/{no}/complete` 人工确认完工（`completionMode=MANUAL`，允许短缺）。

**顺序与版本**：Topic 为普通消息（非顺序），同一工单的事件可能乱序到达。所有工单状态事件都带 `aggregateVersion`，即工单行的乐观锁版本（`work_order.version`），每次状态变更（含下达、冻结、恢复）递增。消费端按工单记录已处理的最大版本，**丢弃版本不高于本地版本的事件**；`occurredAt` 仅用于展示与审计，不能用于排序。

数量均为 `DECIMAL(18,6)` 十进制字符串；`completedQty` 在所有事件中都是**末道工序累计合格数**。

### 事件字段

| Tag | 字段 |
| --- | --- |
| `WorkOrderStarted` | `eventId`、`workOrderNo`、`occurredAt`、`aggregateVersion` |
| `WorkOrderCompleted` | `eventId`、`workOrderNo`、`uom`、`occurredAt`、`completionMode`（`AUTO` / `MANUAL`）、`plannedQty`、`completedQty`、`shortfallQty`（AUTO 固定为 `"0"`；恒有 `plannedQty = completedQty + shortfallQty`）、`reason`（仅 MANUAL）、`aggregateVersion` |
| `WorkOrderReopened` | `eventId`、`workOrderNo`、`occurredAt`、`reversedReportId`（被冲销的原报工）、`completedQty`（冲销后）、`uom`、`aggregateVersion` |
| `WorkOrderReleased` / `WorkOrderHeld` / `WorkOrderResumed` / `WorkOrderHoldReasonCleared` | `eventId`、`orderId`（兼容 V2，同 `workOrderNo`）、`workOrderNo`、业务字段（下达：`productCode`、`quantity`、`bomVersion`、`routingVersion`、`reservationId`；冻结：`reason`、`resumeTarget`；恢复 / 解除原因：`reason`、`status`）、`occurredAt`、`aggregateVersion` |
| `WorkOrderCancelled` | `eventId`、`orderId`、`workOrderNo`、`previousStatus`（取消前状态）、`reservationId`（取消时需释放的预留单，无则为 null）、`occurredAt`、`aggregateVersion` |
| `WorkOrderClosed` | `eventId`、`orderId`、`workOrderNo`、`previousStatus`（恒为 `COMPLETED`）、`note`（可为 null）、`reservationIds`（关闭时转入 `RELEASING` 的预留单号，可为空数组，#72）、`releaseEnqueued`（是否登记了释放任务）、`occurredAt`、`aggregateVersion` |
| `WorkOrderReleaseWithdrawn` | `eventId`、`orderId`、`workOrderNo`、`previousStatus`（恒为 `RELEASED`）、`reservationId`（撤回时需释放的预留单，无则为 null；释放 reason=`CALLER_REQUEST`）、`note`、`occurredAt`、`aggregateVersion` |

`WorkOrderHoldReasonCleared`（多个冻结原因中解除一个，工单仍为 ON_HOLD）不改变状态，携带的是当前版本，按版本去重的消费者会将其丢弃，这是预期行为。V3 之前写入、经回填补发的生命周期事件不含 `aggregateVersion`，消费端按版本 0 处理。

**ID 命名约定**（本文件所有事件通用）：`reversedReportId` 一律指被冲销的**原报工**记录；需要冲销记录本身的 ID 时使用 `reversalId`。

## 库存预留事件（`mes-inventory-reservation`）

库存服务在预留单状态变化时经 outbox 发布事件（与业务数据同事务写入）；工单服务按消费组 `CG_WORKORDER_RESERVATION` 订阅。字段定义以 `docs/api/inventory-openapi.yaml` 的 `Reservation*Event` 为准。

| 项 | 值 |
| --- | --- |
| Topic | `mes-inventory-reservation` |
| Tag | `ReservationCreated`、`ReservationReleased`、`ReservationConsumed`、`ReservationExpired`（与 Nacos `manufacturing.mq.tags.*` 一致） |
| 消息键 | `reservationId` |
| 幂等 | 消费端按 `eventId` 去重 |
| 顺序 | 普通 topic，不保证到达顺序；按 `aggregateVersion` 取最新状态 |
| 续期 | 不发事件；只有持有该预留的调用方能续期，以续期接口返回的 `expiresAt` 为准；未来如需再加 `ReservationRenewed` |

公共字段：`eventId`、`eventType`、`reservationId`、`workOrderNo`、`status`、`aggregateVersion`、`occurredAt`。

- `aggregateVersion`：预留单行的乐观锁版本，按预留单单调递增（续期、部分扣减也会递增，因此可能不连续）。消费端以 `reservationId` 为键，丢弃 `aggregateVersion` 不大于本地已处理版本的事件。
- `occurredAt`：只用于展示和审计，不用于判断先后。
- outbox 发布：同一批中第一次发送失败即结束本批，其余事件不发送、`attempts` 不变，留待下一轮，避免 Broker 不可用时领取事务长时间持锁。

## 工单不良品待质检（`mes-workorder-quality`）

工单服务在工序报工产生不良品、以及冲销带不良数的报工时，经 outbox 向本 Topic 发布事件；质检服务按消费组 `CG_QUALITY_DEFECT` 订阅并建检验单。

| 项 | 值 |
| --- | --- |
| Topic | `mes-workorder-quality` |
| Tag | `DefectReported`（报工不良数 > 0）、`DefectReversed`（冲销带不良数的报工） |
| 生产者 | workorder-service（业务数据与 outbox 同事务写入，由 outbox 中继投递，见下文“工单服务 outbox 中继”） |
| 消费组 | `CG_QUALITY_DEFECT`（质检侧） |
| 幂等 | 消费端按 `eventId` 去重，重复投递无副作用 |

### 事件字段

| 字段 | 说明 |
| --- | --- |
| `eventId` | 事件唯一标识，消费幂等键 |
| `workOrderNo` | 工单号 |
| `operationNo` | 工序号 |
| `materialCode` | 物料编码 |
| `lotNo` | 批次号，**必有**：报工请求带了就用请求值，否则缺省为 `workOrderNo`；`DefectReversed` 与原报工一致。质检回写库存直接透传 |
| `uom` | 计量单位 |
| `defectQty` | 不良数量，`DECIMAL(18,6)`，JSON 中用十进制字符串表示，避免浮点精度丢失 |
| `reportId` | 仅 `DefectReported`：本次报工记录 ID（`DefectReversed` 不含该字段） |
| `occurredAt` | 事件发生时间 |
| `reversedReportId` | 仅 `DefectReversed`：被冲销的原报工记录 ID |
| `reversalId` | 仅 `DefectReversed`：本次冲销记录 ID |

`DefectReversed` 的 `defectQty` 为原报工的不良数（正数），表示需撤销的数量。

## 工单服务 outbox 中继

`WorkOrderOutboxRelay` 沿用库存服务（#18）的模式，投递语义为**至少一次**：

- 每秒一批（`workorder.outbox.interval`），每批最多 50 条（`workorder.outbox.batch-size`），按 `occurred_at` 顺序用 `SELECT … FOR UPDATE SKIP LOCKED` 领取 `status = NEW` 且已到期（`next_attempt_at` 为空或已过）的行，多实例不会重复领取。
- 按行上的 `topic` / `tag` 同步发送（生产者组 `PG_WORKORDER_EVENT`，单条超时 3 秒，客户端不重试），消息键为工单号，消息属性 `eventId` 与消息体一致。
- 成功：`status = SENT`，写 `published_at`。失败：只有该行 `attempts + 1`，按指数退避（1s、2s、4s…上限 5 分钟）写 `next_attempt_at` 与 `last_error`；**本批随即结束**，剩余行不尝试、`attempts` 不变。
- 重发时 `eventId` 不变；Broker 已确认但标记前进程崩溃会导致重复投递，消费端必须按 `eventId` 幂等。

### V3 对 V2 遗留事件的回填

V2 写入的 outbox 行没有投递目标。V3 迁移按事件类型回填：

| V2 事件类型 | 回填结果 |
| --- | --- |
| `WorkOrderReleased`、`WorkOrderHeld`、`WorkOrderResumed`、`WorkOrderHoldReasonCleared` | `topic = mes-workorder-event`，`tag` = 事件类型，保持 `NEW`，由中继补发 |
| 已有 `published_at` 的行 | `status = SENT` |
| 其他无法映射的事件类型 | `status = DISCARDED`，`discard_reason = V3_UNMAPPED_EVENT_TYPE`，**永不投递**，也不计入 `mes_workorder_outbox_pending` |

迁移脚本无法读取 Nacos，回填使用的 Topic / Tag 与 `infra/nacos/common.yml` 当前值一致；修改这些配置值时需同步评估历史数据。

## 冒烟检查

`scripts/smoke-test.sh` 在 broker 容器内用自带的 `mqadmin sendMessage` 向 `mes-smoke-test` 发送一条唯一标记消息，再用新消费组 `consumeMessage` 拉回并比对，验证 NameServer → Broker → 消费的完整链路；不给业务镜像增加任何依赖。`SKIP_MQ_SMOKE=1` 可跳过。CI 的 demo smoke job 通过 `make demo` 自动执行。

## Broker 消息保留期与质检去重保留期

`infra/rocketmq/broker.conf` 显式设置 `fileReservedTime = 72`（小时）。约束：

- 该值必须等于质检服务的 `mes.quality.broker-retention`（默认 PT72H；compose 中以 `MES_QUALITY_BROKER_RETENTION=PT72H` 显式传入，两处一起改；质检服务启动时校验去重保留期 > broker 保留期 + 冲销 TTL）。
- 质检 `consumed_event`、`defect_report_lock` 的保留期必须大于 broker 保留期 + 冲销暂存窗口（`quality.pending-reversal.ttl`，默认 24h），即 > 96h；否则 Broker 仍可能重投的消息在去重记录清理后会被重复处理。

## 工单服务消费库存预留事件

- 订阅 `mes-inventory-reservation`，tag `ReservationCreated || ReservationReleased || ReservationConsumed || ReservationExpired`，消费组 `CG_WORKORDER_RESERVATION`（均取自 Nacos `manufacturing.mq.*`），仅在 `WORKORDER_MATERIAL_RESERVATION=inventory` 时启动。
- 按 `eventId` 幂等（`consumed_event`）；按 `reservationId` 锁定本地预留镜像后比较 `aggregateVersion`：版本**不连续**，只丢弃 `≤` 本地已处理版本的事件，处理后把本地版本推进到事件版本。
- `ReservationExpired`：镜像置 `EXPIRED`，进行中的工单以 `MATERIAL_RESERVATION_EXPIRED` 冻结；已取消工单（镜像 `RELEASING`）只更新镜像。
- 续约不发事件，本地 `expires_at` 只由续约响应更新。

## 工单取消与预留释放

- `POST /workorders/{id}/cancel` 在同一事务内把工单置 `CANCELLED`、写 `WorkOrderCancelled` 事件，并为 ACTIVE 预留写入释放任务 `wo_reservation_release_task`（相当于面向库存 HTTP 接口的 outbox）。
- `ReservationReleaseWorker` 以 `reason=WORK_ORDER_CANCELLED`、`Idempotency-Key: cancel-{工单号}-{预留单号}` 调用库存释放：2xx 且状态 `RELEASED`/`EXPIRED` 为成功；`409 reservation-consumed` 与 `404` 停止任务、写 `wo_inventory_audit` 并告警；5xx/网络错误退避重试；401/403 退避重试并告警。

## 工单关闭与预留释放（#72）

- `POST /workorders/{id}/close` 在同一事务内把工单置 `CLOSED`，并为**每一张** ACTIVE 预留镜像置 `RELEASING`、登记释放任务（`reason=WORK_ORDER_CLOSED`，`Idempotency-Key: close-{工单号}-{预留单号}`）；`CONSUMED`/`RELEASING`/`RELEASED`/`EXPIRED` 不登记。关闭不等待库存结果。
- 任务由同一个 `ReservationReleaseWorker` 执行，结果处理与取消相同（2xx `RELEASED`/`EXPIRED` 成功；`409 reservation-consumed`/`404` 停止 + 审计 + 告警；5xx/网络/401/403 退避重试），指标 `mes_workorder_reservation_release_total` 同样计数。
- 库存 `ReservationReleased.reason` 原样带出 `WORK_ORDER_CLOSED`；工单侧消费该事件时不解析 `reason`，新值不影响镜像推进。
- 线边已领未耗退料与 ERP 退料（M2-B）不在本次范围，见 `docs/workorder-close-reservation-release-design.md` §6。
