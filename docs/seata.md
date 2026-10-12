# Seata AT 分布式事务

使用 Alibaba BOM 管理的 Seata 2.5.0 官方 starter。工单服务为 TM，设备服务与工单服务的数据源通过自动代理作为 RM，独立 TC 使用 manufacturing_seata 保存事务与锁状态。两个业务数据库分别安装 undo_log。只使用自动代理，禁止再手动包装 DataSource。

## 启动与验证

在项目根目录执行，已有数据库卷无需删除；初始化服务会补建协调器库与回滚表。

```powershell
docker compose -f compose.yaml -f compose.rocketmq.yaml -f compose.seata.yaml --profile app --profile mq --profile seata up -d --build
powershell -ExecutionPolicy Bypass -File .\scripts\seata-smoke-test.ps1
```

不需要消息服务时可省略 compose.rocketmq.yaml 和 mq profile。Seata 默认关闭；启用时两个业务服务必须同时设置 SEATA_ENABLED=true、SEATA_SERVER=可达的TC地址。默认事务组 manufacturing_tx_group 映射到 default，开发 TC 直连 seata:8091。TC 堆上限 256 MB、直接内存上限 128 MB；这是开发容量，不是生产性能配置。

TC 镜像以官方 apache/seata-server:2.5.0 为基础，构建时补入 Maven Central 的 MySQL Connector/J 9.2.0。官方镜像不自带该驱动。日志路径和 loader.path 已明确配置。

## 资源预留接口

所有接口需 Bearer JWT；网关和业务服务分别鉴权。ADMIN/OPERATOR 可写，VIEWER 只读。

| 接口 | 权限与用途 |
| --- | --- |
| POST /api/workorders/reservations | workorder:write；JSON 包含 reservationId（1–36位字母、数字或连字符）、deviceId |
| GET /api/workorders/reservations | workorder:read；查询预留单 |
| DELETE /api/workorders/reservations/{id} | workorder:write；全局释放，可重复释放 |
| GET /api/devices/reservations | device:read；查询设备实际占用 |

创建先校验在线设备并插入远程 device_reservation，然后写入本地 work_order_reservation，二者在 @GlobalTransactional 中协调。设备主键禁止重复占用；预留单主键禁止同编号重复创建，冲突返回409并回滚远程分支。创建不是重复请求返回成功的幂等协议：调用超时后应查询原编号确认结果，禁止盲目生成新编号重试。设备离线或不存在时不写入预留。

释放同时删除设备占用和预留单。预留表与已有工单表独立，当前不自动绑定生产工单、更新设备状态或完成排程。预留之外的工单创建仍只查询设备状态，不受资源占用约束；后续业务若要求工单必须占用设备，需要显式扩展契约。

Feign 传播当前全局事务 TX_XID 与经过验证的 Bearer；设备过滤器仅在预留路径绑定 XID，并在 finally 清理上下文。网关删除客户端 TX_XID。设备预留写接口只供内部调用，不在网关开放；需要有效 JWT、写权限和全局事务。TC 与 RM 必须处于受控内网，开发 TC 关闭客户端鉴权，不应暴露到公网。

## 一致性与生产边界

AT 是协调提交和补偿回滚，异常响应后分支恢复可能需要时间。TC 使用 MySQL 持久化并持续重试二阶段失败；不得手工删除 undo_log 或业务行来“修复”尚未完成的事务。生产需部署 TC 高可用、鉴权、TLS/网络隔离、密钥注入、迁移脚本与事务/锁/回滚告警，并演练 TC 中断和脏写恢复。

全局事务仅涉及两个预留表，不写 Outbox、不发送 MQ；普通工单 + Outbox 使用同库本地事务。将来若在全局事务内产生事件，必须先设计全局提交后发布机制，不能直接复用当前轮询发布器。

冒烟脚本使用临时设备完成真实全局提交，再使第二台设备的远程写入成功、本地相同单号插入失败，确认远程 AT 回滚且原预留仍存在；结束时调用全局释放并清理测试设备。自动测试覆盖关闭时拒绝、XID 清理、外部 XID 剥离和预留入口权限。

上游配置和表结构依据 [Seata 2.5.0 官方源码](https://github.com/apache/incubator-seata/tree/v2.5.0/script)。官方 SQL 原文件与许可证保留在 infra/seata；init.sql 为可重复执行的开发初始化组合。
