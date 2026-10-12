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
| Tag | `WorkOrderStarted`（首笔报工，状态转 `IN_PROGRESS`）、`WorkOrderCompleted`（状态转 `COMPLETED`） |
| 生产者 | workorder-service（业务数据与 outbox 同事务写入，投递成功后再标记已发送） |
| 幂等 | 消费端按 `eventId` 去重，重复投递无副作用 |

`WorkOrderCompleted` 在末道工序累计合格数达到计划数时自动发出；也支持人工确认完工。人工完工时事件会带上完工方式（自动或人工）以及与计划数的差额，具体字段名由工单服务实现 PR 确定。

## 工单不良品待质检（`mes-workorder-quality`）

工单服务在工序报工产生不良品、以及冲销带不良数的报工时，经 outbox 向本 Topic 发布事件；质检服务按消费组 `CG_QUALITY_DEFECT` 订阅并建检验单。

| 项 | 值 |
| --- | --- |
| Topic | `mes-workorder-quality` |
| Tag | `DefectReported`（报工不良数 > 0）、`DefectReversed`（冲销带不良数的报工） |
| 生产者 | workorder-service（业务数据与 outbox 同事务写入，投递成功后再标记已发送） |
| 消费组 | `CG_QUALITY_DEFECT`（质检侧） |
| 幂等 | 消费端按 `eventId` 去重，重复投递无副作用 |

### 事件字段

| 字段 | 说明 |
| --- | --- |
| `eventId` | 事件唯一标识，消费幂等键 |
| `workOrderNo` | 工单号 |
| `operationNo` | 工序号 |
| `materialCode` | 物料编码 |
| `lotNo` | 批次号 |
| `uom` | 计量单位 |
| `defectQty` | 不良数量，`DECIMAL(18,6)`，JSON 中用十进制字符串表示，避免浮点精度丢失 |
| `reportId` | 报工记录 ID |
| `occurredAt` | 事件发生时间 |
| `reversedReportId` | 仅 `DefectReversed`：被冲销的原报工记录 ID |

## 冒烟检查

`scripts/smoke-test.sh` 在 broker 容器内用自带的 `mqadmin sendMessage` 向 `mes-smoke-test` 发送一条唯一标记消息，再用新消费组 `consumeMessage` 拉回并比对，验证 NameServer → Broker → 消费的完整链路；不给业务镜像增加任何依赖。`SKIP_MQ_SMOKE=1` 可跳过。CI 的 demo smoke job 通过 `make demo` 自动执行。
