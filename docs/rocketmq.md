# RocketMQ 工单事件链路

使用 Alibaba BOM 管理的官方 RocketMQ 客户端及 Broker 5.3.1。cloud-messaging 仅共享配置与客户端依赖，不引入其他 Boot 版本的自动配置。event-service 维护独立消费数据库。

## 数据可靠性

创建工单时，工单和 WorkOrderCreated 事件在同一个 MySQL/H2 本地事务中写入。写入任一失败会全部回滚。请求线程不发送 MQ，因此 Broker 故障不会丢失已提交事件，也不会阻止已通过设备校验的工单创建。

后台逐条抢占 Outbox 租约并发送。只有 SEND_OK 后标记 SENT；失败按指数退避重试，最多八次，然后转为 FAILED。进程在发送后、确认前崩溃可能重复发送，因此保证的是至少一次投递，不承诺消息层恰好一次。

消费端将 processed_event 幂等标记与工单投影放在一个事务内。相同 eventId 和相同内容的重投只确认，不重复产生投影；同 ID 不同内容拒绝并重试。业务投影失败时标记回滚。消费六次重试后交由 Broker 进入消费组死信队列，需运维排查与受控重投。

事件包含版本、类型、eventId、orderId、设备、产品、数量、时间与请求关联 ID，不携带 Bearer 令牌、密码或私钥。事件的来源信任依赖 MQ 的访问隔离；开发 Broker 未配置 ACL，生产需配置身份授权及网络隔离。

## 启动

全部服务属于 manufacturing-cloud。普通 local 和 app 模式默认关闭 MQ 连接，但仍持久化发件箱；启用后补发积压。

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\init-auth.ps1
docker compose -f compose.yaml -f compose.rocketmq.yaml --profile app --profile mq up -d --build
powershell -ExecutionPolicy Bypass -File .\scripts\smoke-test.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\rocketmq-smoke-test.ps1
```

初始化容器完成后显示 Exited (0) 属正常行为：rocketmq-volume-init 修正专用数据卷权限，rocketmq-init 显式创建 Topic，event-db-init 创建事件库和独立账号。

NameServer 绑定 localhost:9876，Broker 绑定 localhost:10911 / 10909。容器模式发布 Broker 容器 IP，不能直接用于本机 Java 客户端。若要本机 Java + 容器 Broker，设置 ROCKETMQ_BROKER_ADVERTISE_ADDR 为本机和容器均可达的宿主机 IP 后重建 Broker，再在启动 Java 前设置 ROCKETMQ_ENABLED=true 和 ROCKETMQ_NAMESERVER=localhost:9876；同时保证对应端口可达。

开发环境 NameServer 堆上限 128 MB，Broker 堆上限 256 MB，业务服务堆上限 256 MB。Broker 禁用本阶段未使用的定时消息存储，使用同步刷盘和单节点；生产需按负载配置容量、多副本、ACL、监控与备份。

## 查询和权限

- GET /api/events/workorders：最近一百条成功消费投影，要求 workorder:read。
- GET /api/workorders/outbox：发件箱状态计数，要求 metrics:read（管理员）。
- POST /api/workorders/outbox/{eventId}/retry：管理员重试 FAILED 事件，事件 ID 和负载保持不变。

Outbox PENDING/PROCESSING 积压、FAILED 数量及消费组死信均需告警。SENT 表示 Broker 已确认，并不表示业务消费者已完成。消费结果应查询事件投影。

## 配置

| 配置 | 默认 |
| --- | --- |
| ROCKETMQ_ENABLED | false，启用叠加 Compose 后为 true |
| ROCKETMQ_NAMESERVER | localhost:9876，容器内 rocketmq-nameserver:9876 |
| ROCKETMQ_TOPIC | manufacturing-workorders |
| ROCKETMQ_PRODUCER_GROUP | manufacturing-workorder-producer |
| ROCKETMQ_CONSUMER_GROUP | manufacturing-workorder-events |
| messaging.rocketmq.send-timeout-ms | 3000 |
| messaging.rocketmq.max-attempts | 8 |
| messaging.rocketmq.lease-seconds | 60 |
| messaging.rocketmq.retry-base-seconds | 2，最大延迟 300 秒 |

修改 Topic 时也要显式创建相应 Topic。更改消费组可能重放历史消息。禁止随意删除幂等记录；生产应为 Outbox 查询配置索引、分页清理已发送记录，并采用数据库迁移工具管理表结构。

## 停止

```powershell
docker compose -f compose.yaml -f compose.rocketmq.yaml --profile app --profile mq stop
```

此命令仅停止 manufacturing-cloud，保留数据库、消息存储和密钥数据卷，不关闭其他 Docker 项目。
