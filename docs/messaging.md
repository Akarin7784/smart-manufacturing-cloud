# 消息中间件（RocketMQ）

演示环境用 RocketMQ 5.5.0（`apache/rocketmq` 官方镜像，remoting 协议）承载领域事件，随 `make demo`（compose `app` profile）启动。

## 组件

| 容器 | 作用 | 健康检查 | 资源 |
| --- | --- | --- | --- |
| `namesrv` | NameServer，宿主机仅绑定 `127.0.0.1:9876` | `/dev/tcp` 探测 9876 | 堆 ≤256M |
| `broker` | 单主 Broker，配置见 `infra/rocketmq/broker.conf`，不对宿主机暴露端口 | `/dev/tcp` 探测 10911 | 堆 ≤512M，直接内存 ≤256M，CommitLog 单文件 128M |
| `rocketmq-init` | 一次性容器：确认 Broker 已注册后按 `infra/rocketmq/topics.txt` 幂等建 Topic | 成功退出 | 工具堆 128M |

- Broker 关闭了自动建 Topic，新 Topic 必须登记到 `infra/rocketmq/topics.txt`。
- 消息不落数据卷，`down` 后即丢弃，仅适用于演示。
- JVM 参数可在 `.env` 用 `ROCKETMQ_NAMESRV_JAVA_OPT` / `ROCKETMQ_BROKER_JAVA_OPT` 覆盖；共享机器上不要调大 Broker 堆。
- 演示环境未开启 ACL，因此没有密钥；生产环境需开启 ACL 并通过平台 secrets 注入。

## 共享配置（Nacos `MANUFACTURING/common.yml`）

| 键 | 值 | 说明 |
| --- | --- | --- |
| `rocketmq.name-server` | `${ROCKETMQ_NAMESRV_ADDR:localhost:9876}` | compose 统一注入 `namesrv:9876` |
| `manufacturing.mq.topics.inventory-reservation` | `mes-inventory-reservation` | 库存预留事件，库存服务经 outbox 发布 |
| `manufacturing.mq.topics.workorder-event` | `mes-workorder-event` | 工单状态事件 |
| `manufacturing.mq.tags.*` | `ReservationCreated` / `ReservationReleased` / `ReservationConsumed` / `ReservationExpired` | 同一 Topic 按 Tag 区分事件类型 |
| `manufacturing.mq.consumer-groups.workorder-reservation` | `CG_WORKORDER_RESERVATION` | 工单服务订阅预留事件 |

`ReservationExpired` 由库存服务定时任务判定后发布，工单服务只订阅、不自行计算过期。服务代码通过 `@ConfigurationProperties("manufacturing.mq")` 引用，禁止硬编码 Topic。

## 冒烟检查

`scripts/smoke-test.sh` 在 broker 容器内用自带的 `mqadmin sendMessage` 向 `mes-smoke-test` 发送一条唯一标记消息，再用新消费组 `consumeMessage` 拉回并比对，验证 NameServer → Broker → 消费的完整链路；不给业务镜像增加任何依赖。`SKIP_MQ_SMOKE=1` 可跳过。CI 的 demo smoke job 通过 `make demo` 自动执行。
