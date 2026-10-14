# quality-service 设计稿（已拍板）

基线：main `0ed2828`。事件字段以 #20（`feat/workorder-reporting`）为准。契约见 `docs/api/quality-openapi.yaml`。
参照 Opcenter NCR/MRB、鼎捷不良处置：报工发现不良 → 生成 NCR → 评审 → 处置 → 关闭。

## 1. 消费 `mes-workorder-quality`（消费组 `CG_QUALITY_DEFECT`）
- **DefectReported**：`eventId, workOrderNo, operationNo, materialCode, lotNo, uom, defectQty, reportId, occurredAt`（`lotNo` 必有，缺省为 `workOrderNo`）。一次报工建一张 NCR，按 `reportId` 唯一。
- **DefectReversed**：只认 `reversedReportId`（被冲销的原报工）和 `reversalId`（冲销记录本身）；#20 将去掉 `reportId`。按 `reversedReportId` 找 NCR。
- **字段口径**：`materialCode` 是工单产品码。不良事件不带 `aggregateVersion`，NCR 与报工一一对应，靠下面的规则处理乱序。
- **幂等**：`consumed_event(event_id PK)` 与业务写入同一事务，主键冲突直接 ACK；`ncr.source_report_id` 唯一作第二道保险。
- **坏消息（#30）**：JSON 解析失败、缺 `eventId`、缺必填字段、数量格式错误等不可恢复的消息，不再交给 RocketMQ 重投（原先按退避重投 16 次后才进 `%DLQ%CG_QUALITY_DEFECT`）。
  - 写入 `invalid_event`（原始 msgId、tag、错误原因、载荷 SHA-256、字节数、前 512 个字符摘录）后直接 ACK，计数 `mes_quality_event_consumed_total{result="invalid"}`（启动预注册为 0），告警 `QualityInvalidEvent` 由运维添加。
  - 留档写入失败、数据库或网络等可恢复错误仍回滚并重投（`result="error"`）。
- **去重表保留期（#31）**：`consumed_event` 与 `defect_report_lock` 只保留 `quality.dedup.retention`（默认 `P30D`），每天 03:47 按创建时间分批清理（每批 1000 行一个事务，`FOR UPDATE SKIP LOCKED`，受 `quality.jobs.enabled` 控制），计数 `mes_quality_cleanup_deleted_total{table}`。
  - `mes.quality.broker-retention`（默认 `PT72H`）必须等于 Broker 的 `fileReservedTime`；启动时校验保留期 > `broker-retention` + `quality.pending-reversal.ttl`，不满足则启动失败。
  - 清理后迟到的重复报工由 `ncr.source_report_id` 唯一约束兜底，不会生成第二张 NCR。
- **乱序：冲销先于报告到达，暂存待对冲**：
  - 找不到 NCR 时写 `pending_reversal(reversed_report_id PK, reversal_id, event_id, received_at, status=WAITING)`，然后 ACK，不阻塞消费位点。
  - 原报工在过期前到达：同一事务内命中暂存，直接建 `VOIDED` 的 NCR（`void_reason=REVERSED_BEFORE_REPORTED`），暂存标 `MATCHED`。只发 `NcrVoided`，不发 `NcrOpened`。
  - **超时兜底**：定时任务把 `WAITING` 超过 `quality.pending-reversal.ttl`（默认 `PT24H`）的记录标 `EXPIRED`（相当于死信），计数 `mes_quality_pending_reversal_expired_total`，告警 `QualityReversalOrphan`，人工按 `reversedReportId` 去工单侧核对。不用 MQ 重试或 DLQ 等待。
  - 过期后原报工才到：按正常 Reported 建 `OPEN` 的 NCR，暂存保持 `EXPIRED`，由人工处理。
- **已处置的 NCR 收到冲销**：不回退，因为实物已经返工或报废。
  - NCR 状态和处置数量不变，写 `ncr_conflict(event_id, ncr_no, type=DISPOSED_REVERSAL_CONFLICT, reversal_id, disposed_qty)`，然后 ACK。
  - 计数 `mes_quality_event_consumed_total{result="disposed_reversal_conflict"}`，告警 `QualityDisposedReversalConflict`（>0 即告警）。
  - 消费侧没有 HTTP 可返回，所以不对应任何 Problem type；冲突可经 `GET /api/quality/conflicts` 查询。
  - 未处置（`OPEN` 或 `UNDER_REVIEW`，没有任何处置行）的 NCR 收到冲销：转 `VOIDED`。

## 2. 领域模型
- **NCR**：`ncr_no`、`source_report_id`（唯一）、`work_order_no`、`operation_no`、`material_code`、`lot_no`、`uom`、`defect_qty`、`disposed_qty`、`status`、`version`。
- **状态机**：
  - `OPEN →(review) UNDER_REVIEW →(处置累计 = 不良量) DISPOSED →(close) CLOSED`
  - 未处置时收到冲销转 `VOIDED`，终态。
  - `UNDER_REVIEW` 期间允许多次部分处置。
- **处置行**：`type` 取 `REWORK`、`SCRAP`、`CONCESSION`，另有 `qty`、`uom`、`reason`、`operator`、`disposed_at`。只追加，不修改。
- **数量守恒**：`Σqty = disposed_qty ≤ defect_qty`，相等时自动转 `DISPOSED`。用 `DECIMAL(18,6)` 十进制字符串；`uom` 必须等于 NCR 的单位，不换算。登记处置时 `SELECT … FOR UPDATE` 锁 NCR 行；幂等写事务用 READ COMMITTED。
- **单人处置**：只要有 `quality:write` 就能处置，本期不做审批流。

## 3. API（`/api/quality/**`，用户令牌：查询需 `quality:read`，写入需 `quality:write`）
- **接口**：
  - `GET /ncrs`、`GET /ncrs/{ncrNo}`
  - `POST /ncrs/{ncrNo}/review`、`POST /ncrs/{ncrNo}/dispositions`、`POST /ncrs/{ncrNo}/close`
  - `GET /pending-reversals`、`GET /conflicts`
- 所有 POST 必须带 `Idempotency-Key`（作用域为用户 sub，保留 7 天），重放时原样返回首次状态码与响应体。
- 不提供手工建 NCR 的接口。
- **Problem type 固定枚举**（RFC 7807，`application/problem+json`）：

| type | HTTP | 含义 |
| --- | --- | --- |
| `validation-error` | 400 | 参数校验失败 |
| `idempotency-key-conflict` | 422 | 同一幂等键已用于不同请求体 |
| `uom-mismatch` | 422 | 处置单位与 NCR 单位不一致 |
| `over-dispose` | 422 | 处置数量超过剩余待处置量 |
| `invalid-status-transition` | 409 | 当前状态不允许该操作（例如 OPEN 直接 close） |
| `ncr-voided` | 409 | NCR 已作废，不能再评审、处置或关闭 |
| `ncr-not-found` | 404 | NCR 编号不存在（契约中所有 NCR 的 404 均用它） |

共 7 种；401/403 不在此列，分别为 `authentication-required`（title“需要认证”）与 `access-denied`（title“没有访问权限”），见 docs/authentication.md 的服务 Problem 表。

## 4. 与库存的交互
- **本期不做**：SCRAP 只登记处置记录，不调用库存，也不改动库存（契约中写明）；REWORK 和 CONCESSION 同样只记录。
- **下期**：处置回写库存，包括 `inventory_writeback` 任务、固定 `Idempotency-Key`、退避重试、库存服务令牌、`QC-HOLD` 库位、回写指标和告警，以及回写 IT。
- **不做**：RETURN_TO_SUPPLIER、手工 NCR、审批流、IQC/FQC/SPC。

## 5. 事件、指标、运维
- **Topic `mes-quality-event`**：tag 为 `NcrOpened`、`NcrDisposed`、`NcrClosed`、`NcrVoided`，带 `aggregateVersion`（取 `ncr.version`）。本期只发不消费。outbox 规则同库存：首次失败即结束本批。
- **指标**：
  - `mes_quality_api_total{result}`，result 取 `ok`、`replay`、7 种 type 的下划线写法（含 `ncr_not_found`）、`error`
  - `mes_quality_ncr_transition_total{from,to}`，提交后计数，新建 NCR 的 `from` 为 `NONE`
  - `mes_quality_disposition_qty_total{type,uom}`
  - `mes_quality_event_consumed_total{tag,result}`（result 含 `invalid`）
  - `mes_quality_pending_reversal_expired_total`
  - `mes_quality_cleanup_deleted_total{table}`
  - `mes_quality_outbox_pending`
  - `mes_quality_ncr_open`
- **Flyway V1**：表 `ncr`、`ncr_disposition`、`consumed_event`、`pending_reversal`、`ncr_conflict`、`outbox_event`、`idempotency_record`。
- **Flyway V2**：新增 `invalid_event`；`defect_report_lock` 增加 `created_at`；两张去重表加时间索引。
- **部署**：compose、网关路由和管理端口 18086 归运维，契约 PR 不改。

## 6. 合入门槛（Testcontainers：真实 RocketMQ + MySQL 8.4，经 broker 投递）
1. **重复投递**：同一 DefectReported 投递 3 次，只生成 1 条 NCR。
2. **冲销先于报告**：
   - 先冲销、后报工（过期前），NCR 为 `VOIDED`，暂存为 `MATCHED`。
   - 只有冲销时，用可注入的 `Clock` 推进超过 TTL，断言暂存为 `EXPIRED`，过期计数 +1。
   - 暂存已 `EXPIRED` 后原报工才到达：生成 `OPEN` 的 NCR（并发 `NcrOpened`），暂存保持 `EXPIRED`，不自动对冲。
3. **已处置的 NCR 被冲销**：NCR 状态和处置数量不变，有 1 条 `DISPOSED_REVERSAL_CONFLICT` 冲突记录，冲突计数 +1，消息已 ACK、没有重投。
4. **契约测试**：每个响应按 OpenAPI 校验，7 种 type 全部触发；并发处置不超处置；幂等重放。
