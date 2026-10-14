# 工单关闭释放剩余预留设计稿（M2 / #72，待评审）

> 范围：COMPLETED → CLOSED 时释放仍为 ACTIVE 的物料预留；线边已领未耗与 ERP 退料只定边界，本 PR 不落地。
> 依据：#72、#70 关闭实现、`WorkOrderCancellation` / `WorkOrderClosure.withdrawInTx` 释放任务、库存 `POST …/release`（整单释放未扣减量）。
> 作者：蓝色大肥鱼；状态：待 Eva 拍板后落仓并开实现 PR。

## 1. 现状

- `closeInTx`（#70）只改状态、写审计与 `WorkOrderClosed`，**不**碰 `wo_material_reservation`。
- 取消：ACTIVE → RELEASING，登记 `wo_reservation_release_task`，`reason=WORK_ORDER_CANCELLED`，键 `cancel-{工单号}-{预留单号}`。
- 撤回下达：同上，`reason=CALLER_REQUEST`，键 `withdraw-{工单号}-{预留单号}`。
- 库存释放：ACTIVE 整单释放未扣减量 → RELEASED；已 RELEASED/EXPIRED 幂等 200；CONSUMED → 409 `reservation-consumed`（Worker 已终态 + 审计）。
- 领域稿：领料 / 线边库属 P2，ERP 属 P4；当前没有领料单、线边批次余量表。

## 2. 目标与非目标

**目标（M2-A，本设计可立即实现）**

1. 关闭时若存在 ACTIVE 预留镜像：置 RELEASING，登记释放任务，后台 `ReservationReleaseWorker` 调库存释放。
2. 新增释放原因 `WORK_ORDER_CLOSED`（工单侧 enum + 库存侧 enum + OpenAPI `ReleaseReason` 同步）。
3. `WorkOrderClosed` 事件补齐释放相关字段；更新 `docs/messaging.md`、`workorder-openapi.yaml`。
4. 测试：H2 集成 + MySQL IT；覆盖无 ACTIVE、有 ACTIVE、已 CONSUMED（Worker 终态）、库存 5xx 重试。

**非目标（M2-B，另开 issue，依赖 P2 领料）**

- 线边已领未耗的退料单、库存回冲与预留释放的交错顺序。
- ERP 退料适配器与失败补偿（P4）。
- 关闭时按行“部分释放”新 API（现有整单释放已归还未扣减量，够用）。

## 3. 推荐默认（请 Eva 一次确认或推翻）

| # | 议题 | 推荐 | 理由 |
| --- | --- | --- | --- |
| Q1 | 关闭是否等库存释放成功 | **不等**（与取消 / 撤回一致：事务内只登记任务） | 关闭已过 NCR 门禁；阻塞会把库存抖动变成关闭 503 |
| Q2 | reason / 幂等键 | `WORK_ORDER_CLOSED`；键 `close-{工单号}-{预留单号}` | 与 cancel/withdraw 并列，审计可区分；键前缀避免与 cancel/withdraw 撞唯一约束 |
| Q3 | 多张 ACTIVE 预留 | 每张各登记一条任务（当前业务通常一张，代码按列表循环） | 与取消侧“取第一张”不同：关闭应扫干净，避免漏释 |
| Q4 | 镜像已是 RELEASING / RELEASED / CONSUMED / EXPIRED | 不登记新任务 | 避免重复；Worker / 事件消费已推进终态 |
| Q5 | Worker 遇 `reservation-consumed` / 404 | 沿用现规则：任务 DONE + `wo_inventory_audit` + 告警 | 关闭场景常见（完工倒冲已吃光预留） |
| Q6 | `WorkOrderClosed` 字段 | 增加 `reservationIds`（关闭时转入 RELEASING 的列表，可空数组）与 `releaseEnqueued`（bool） | 消费方可知是否有释放在途；不把释放结果塞进关闭事件（结果仍靠 ReservationReleased） |
| Q7 | LocalMaterialReservation | 同步支持 `WORK_ORDER_CLOSED`，行为与其它 reason 相同 | demo / 单测不依赖库存服务 |
| Q8 | M2-B 触发条件 | 仅当存在「已领未耗」流水时才生成退料；无领料模块前 **关闭只做 M2-A** | 避免空转 ERP |

## 4. 关闭事务内改动（`WorkOrderClosure.closeInTx`）

在状态更新与 `WorkOrderClosed` 出站之前（同一事务）：

1. `SELECT reservation_id FROM wo_material_reservation WHERE order_id = ? AND status = 'ACTIVE' FOR UPDATE`（或依赖已锁工单行的串行化；实现时与取消路径对齐加锁顺序）。
2. 对每个 id：`UPDATE … SET status = 'RELEASING'`；`INSERT wo_reservation_release_task (…, reason='WORK_ORDER_CLOSED', idempotency_key='close-'+orderId+'-'+reservationId, status='PENDING', …)`。唯一键冲突（重复关闭被幂等挡住前的竞态）按已存在处理，与补偿释放一致。
3. 事件 payload：`previousStatus`、`note`、`reservationIds`、`releaseEnqueued`（`reservationIds` 非空则为 true）。

关闭 HTTP 响应体可不暴露任务细节（保持 `WorkOrderCloseView` 精简）；需要时可后续加 `releaseStatus=PENDING`，本版不做。

## 5. 库存契约

- `ReleaseReason` 增加 `WORK_ORDER_CLOSED`；`ReservationReleased.reason` 原样带出。
- 释放语义不变：归还未扣减量；不引入部分释放 API。
- 工单 `MaterialReservationPort.ReleaseReason` 与库存枚举保持同名同序新增。

## 6. 与 M2-B（退料）的衔接（仅边界）

```
关闭
  ├─ M2-A：ACTIVE 预留 → 释放任务（本设计）
  └─ M2-B（未来）：Σ(已领 − 已耗/已倒冲) > 0
        → 登记退料任务（线边 → 仓，或 ERP 退料单）
        → 成功后再视需要补释放 / 审计
```

顺序建议（M2-B 评审时再锁）：先退料回仓，再释放仍 ACTIVE 的预留；若预留已在 M2-A 释放，退料只动线边实物。**本设计不实现 M2-B。**

## 7. 测试清单

| 用例 | 期望 |
| --- | --- |
| COMPLETED + 无预留行 | 关闭成功；无释放任务；事件 `reservationIds=[]` |
| COMPLETED + ACTIVE 预留 | 镜像 RELEASING；任务 PENDING / reason=WORK_ORDER_CLOSED / 键 close-…；事件带 id |
| Worker 释放成功 | 任务 DONE；库存 RELEASED；工单侧镜像随事件变 RELEASED |
| 预留已 CONSUMED（完工吃光） | 关闭不登记任务；若误登记则 Worker 终态 + 审计 |
| 库存 503 | 任务退避重试；工单保持 CLOSED |
| 同键重放关闭 | 不二次插入任务（关闭幂等在 `wo_command_request`） |
| Local 后端 | reason 枚举可解析；单测绿 |

## 8. 实现切片建议

1. 契约：OpenAPI + messaging.md + 两侧 `ReleaseReason`。
2. `closeInTx` + 事件字段 + H2 / MySQL IT。
3. （可选同 PR）观测：`mes_workorder_reservation_release_total` 已按 result 计，无需新指标；文档补一句关闭也会入队。

## 9. 待 Eva 回复

回复「按默认」即可按 §3 开 PR；若要改，请点名 Q1–Q8。确认后我会把本稿标为已评审并推到 `docs/`，再交给实现同学。
