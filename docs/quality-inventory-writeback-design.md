# 质检回写库存设计（已拍板）

> 拍板：蓝色大肥鱼（2026-10-12）。顺序：本设计 + 契约（本 PR）→ 实现 PR。
> 契约：`docs/api/quality-openapi.yaml`、`docs/api/inventory-openapi.yaml`（新增部分标 `x-planned: true`），指标与错误类型见 `docs/observability.md` §5。
> 前序设计：`docs/quality-design.md` §4（上期"本期不做"的回写在此定义）。

## 0. 现状
- 认证服务已支持 OAuth2 client_credentials（`POST /auth/oauth2/token`，网关 `/api/auth/oauth2/token`），令牌带 `client_id`、`scope`、`token_type=service`，`aud` 为单个资源服务，TTL 默认 300s。客户端注册在 auth-service `application.yml` 的 `manufacturing.auth.clients`，现有 `workorder-service`（`inventory:reserve`）与 `quality-service`（`inventory:qc-hold`，本设计新增）。
- 库存服务按路径分过滤链：`/inventory/reservations/**` 只认服务令牌；其余接口只认用户令牌（`inventory:read` / `inventory:write`）。
- 质检：一次报工一张 NCR（`material_code` 为工单产品编码、`lot_no`、`uom`、`defect_qty`），处置类型 `REWORK` / `SCRAP` / `CONCESSION`。

## 1. 决策
| # | 事项 | 决定 |
| --- | --- | --- |
| 1 | 服务令牌 scope | 新增客户端 `quality-service`，scope **`inventory:qc-hold`**，aud `inventory-service`；不复用用户权限 `inventory:write`。客户端 scope 维护在 auth-service `manufacturing.auth.clients`，不在 `contracts/auth/role-permissions.json`（那是用户角色表，见 `contracts/auth/README.md`） |
| 2 | 通用出入库 | `/inventory/inbound`、`/inventory/outbound` **不接受服务令牌**。只新增 `qc-hold/receipts`、`qc-hold/releases`、`qc-hold/scraps`；库位由服务端固定为 `QC-HOLD`，请求体不得带库位（多余字段 400） |
| 3 | 进隔离 | NCR 以 `OPEN` 建成时把不良数量记入 `QC-HOLD`：物料 = 工单产品编码，批次、单位取自不良事件。隔离量不计入现存量合计与可用量、不可预留；现存量与可用量查询单独返回隔离量（`quarantined`） |
| 4 | REWORK / CONCESSION | 从 QC-HOLD 放行出库，原因 `REWORK` / `CONCESSION`，**不指定目标库位** |
| 5 | 作废 | 已入 QC-HOLD 的 NCR 因冲销作废 → 放行出库，原因 `NCR_VOIDED` |
| 6 | 关闭 | NCR 仍有 `PENDING`、`RUNNING` 或 `FAILED` 回写任务时关闭返回 409 `writeback-pending` |
| 7 | 运维接口 | `GET /ncrs/{ncrNo}/writebacks`（`quality:read`）；`POST .../writebacks/{id}/retry` 与 `POST .../writebacks/{id}/resolve`（`quality:write`，均需 `Idempotency-Key`），仅 `FAILED` 可操作，否则 409 **复用 `invalid-status-transition`** |
| 8 | 重试窗口 | `mes.quality.writeback.retry-window`，默认 `P6D`，必须小于库存幂等记录保留期（7 天）；超窗口重试 409 |
| 9 | 人工结案 | `resolve` 必填 `reason`（空白 400），任务置终态 `RESOLVED_MANUALLY` 并写审计；计入 `result="resolved_manually"` |

## 2. 令牌与校验
- 质检服务持有 `QUALITY_CLIENT_SECRET`（明文仅质检持有），auth 只存 BCrypt 散列 `QUALITY_CLIENT_SECRET_HASH`（init-env 生成，cost 10）；cloud 环境缺散列启动失败。质检侧配置键为 `mes.quality.writeback.client.{client-id,client-secret,scope}`。**已落地**：客户端注册、init-env、compose 与 Nacos 注入（auth 与部署 PR）；网关对 `/api/inventory/qc-hold/**` 返回 404（内部路径清单）；库存 `/inventory/qc-hold/**` 过滤链由库存实现 PR 落地。
- 质检缓存令牌，剩余有效期 < 60s 时刷新；收到 401 作废缓存重取一次。
- 服务间调用经 Nacos 服务发现直连 `inventory-service`，**不经网关**。网关规则：`/api/inventory/qc-hold/**` 不对外转发（拒绝），服务令牌不含 `permissions`，网关现有角色规则对其不适用。
- 库存新增过滤链 `/inventory/qc-hold/**`：解码器只认 `aud=inventory-service`（不符 401），授权要求 `SCOPE_inventory:qc-hold`（不足 403）；`inventory:reserve` 令牌不能调用隔离接口，反之亦然。错误为 problem+json。

## 3. 库存接口（`x-planned`）
| 接口 | 流水 `txnType` | 幂等键 |
| --- | --- | --- |
| `POST /inventory/qc-hold/receipts` | `QC_HOLD_IN` | `qc:{ncrNo}:hold` |
| `POST /inventory/qc-hold/releases`（`reason` = `REWORK`/`CONCESSION`） | `QC_HOLD_RELEASE` | `qc:{ncrNo}:disp:{dispositionId}:release` |
| `POST /inventory/qc-hold/releases`（`reason` = `NCR_VOIDED`） | `QC_HOLD_RELEASE` | `qc:{ncrNo}:void` |
| `POST /inventory/qc-hold/scraps` | `SCRAP` | `qc:{ncrNo}:disp:{dispositionId}:scrap` |

- 请求：`ncrNo, materialCode, uom, lotNo, qty`，releases 另有 `reason`、`dispositionId`，scraps 另有 `dispositionId`；`refType=NCR`、`refNo=ncrNo`，`operator` 为 client_id。幂等作用域为 client_id，保留 7 天。
- `QC-HOLD` 为迁移种子系统库位，通用出入库对它返回 400 `validation-error`；隔离量不足返回 409 `insufficient-stock`（说明数据不一致）；单位不一致 422 `uom-mismatch`。
- `Availability` 新增 `quarantined`（隔离量）；`Stock` 行新增 `inQcHold: boolean`。库存不新增 Problem type。
- `releases`：`reason` 为 `REWORK`/`CONCESSION` 时必须带 `dispositionId`，`NCR_VOIDED` 时禁止携带（schema 用 if/then/else 表达），违反 400 `validation-error`。
- 隔离接口在契约中用操作级 `servers` 标为内部直连（`http://inventory-service:8085`，路径 `/inventory/qc-hold/**`），不经网关。

## 4. 质检侧时序
- **入隔离**：消费 DefectReported 建 `OPEN` NCR 的同一事务写 HOLD 任务。报工前已冲销而直接 `VOIDED` 的 NCR 不写任务。
- **处置**：登记处置行的同一事务写 SCRAP 或 RELEASE 任务（处置行只追加，`dispositionId` 稳定，键固定）。
- **作废**：NCR 转 `VOIDED` 的同一事务内：
  - HOLD `first_attempted_at` 为空（从未被领取执行）且不在执行中（非 `RUNNING`）→ HOLD 置 `CANCELLED`，不写 VOID。不用 `attempts=0` 判断：人工重试会把 attempts 清零，`first_attempted_at` 永不清空（#55 拍板）；
  - 否则（已尝试过或正在执行，库存是否已记账不确定）→ 保留 HOLD，追加 VOID 任务，同一 NCR 串行，VOID 排在 HOLD 之后：
    - HOLD 最终 `DONE` → 执行 VOID；
    - HOLD 因**明确拒绝**（400、422、409 `insufficient-stock`）`FAILED` → 库存确定未记账，VOID 自动 `CANCELLED`；
    - HOLD 因**结果不确定**（超时、5xx 重试耗尽）`FAILED` → VOID 保持 `PENDING` 排在其后；人工重试 HOLD 沿用同一幂等键（库存若已记账则重放首次结果），HOLD `DONE` 后执行 VOID；若人工确认库存未记账，对 HOLD 执行 `resolve`（`RESOLVED_MANUALLY`），其后的 VOID 自动 `CANCELLED`。
- **已处置被冲销**：保持现行 `DISPOSED_REVERSAL_CONFLICT`，不自动回滚库存。
- **关闭**：有 `PENDING`/`FAILED` 任务 → 409 `writeback-pending`；全部为终态 `DONE`/`CANCELLED`/`RESOLVED_MANUALLY` 才允许关闭。

## 5. 任务表与重试
- `inventory_writeback(id, ncr_no, action[HOLD|SCRAP|RELEASE|VOID], reason, disposition_id, idempotency_key UNIQUE, material_code, lot_no, uom, qty, status[PENDING|RUNNING|DONE|FAILED|CANCELLED|RESOLVED_MANUALLY], attempts, next_attempt_at, last_error, inventory_txn_id, created_at, updated_at)`。
- 调度每 2s 取到期 `PENDING` 及租约到期的 `RUNNING`（`FOR UPDATE SKIP LOCKED`，每批 50），领取即置 `RUNNING`（租约 60s），事务外调用库存后回写状态。**同一 NCR 按 id 顺序串行**：前序任务为 `PENDING`、`RUNNING` 或 `FAILED` 时后续不执行，保证先入后出；`DONE`、`CANCELLED`、`RESOLVED_MANUALLY` 不阻塞。
- 可重试：网络错误、429、503 及其他 5xx、令牌端点 5xx 或不可达 → 指数退避 2s 起、上限 5min，最多 20 次后 `FAILED`（结果不确定）。
- 不可重试（直接 `FAILED`，`last_error` 记 problem type 与 detail）：400、403、404（库位 / 物料不存在）、409 `insufficient-stock`、422；401 先作废令牌重取并重试一次，仍 401 则 `FAILED`；令牌端点 4xx 同样 `FAILED`。401/403/令牌 4xx 计 `mes_quality_inventory_auth_failure_total{operation=hold|release|scrap|token}`。
- 422 `idempotency-key-conflict`：`FAILED` 不重试，但不能证明库存未记账，其后的 VOID **不**自动取消；与 400、未约定响应一起计 `mes_quality_inventory_contract_mismatch_total{operation}`（口径同工单服务同名指标）。
- `dispositionId` 取处置行在 NCR 内的 `line_no`，不保证全局唯一，库存以 `ncrNo` + `dispositionId` 定位；`lotNo` 原样透传报工事件中的值，不设默认批次。
- 人工重试：`FAILED` → `PENDING`、`attempts` 清零，幂等键不变。库存幂等记录只保留 7 天，超期重放可能重复记账，因此重试窗口 `mes.quality.writeback.retry-window`（默认 `P6D`，配置注释写明必须小于库存幂等保留期 7 天）外的重试返回 409 `invalid-status-transition`，只能人工核对后 `resolve`。
- 人工结案：`resolve` 仅 `FAILED` 可用（否则 409 `invalid-status-transition`），`reason` 必填（空白 400 `validation-error`），需 `Idempotency-Key`（同键不同请求体 422 `idempotency-key-conflict`）；置 `RESOLVED_MANUALLY`、写审计（操作人 sub、原因、时间）；HOLD 被结案时其后的 VOID 置 `CANCELLED`。
- `DONE`/`CANCELLED`/`RESOLVED_MANUALLY` 行保留 P30D，接入现有 03:47 清理任务。
- 数量为十进制字符串，`stripTrailingZeros().toPlainString()`。

## 6. 指标与告警
- `mes_quality_writeback_total{result}`：`ok`/`replay`/`retry`/`failed`/`cancelled`/`resolved_manually`，6 个序列启动全部预注册为 0。
- `mes_quality_writeback_pending`（Gauge），启动即 0。
- `mes_quality_api_total{result}` 增加 `writeback_pending`。
- 告警由运维添加：`failed` 增量 > 0 告警；`pending` 持续 > 0 超过 10 分钟告警；`resolved_manually` 增量 > 0 为 info 告警 `QualityWritebackResolvedManually`，并加看板面板。冒烟断言相关序列以 0 预注册。

## 7. 测试（实现 PR）
- 质检：任务写入与键格式、同 NCR 串行、401 刷新、5xx 退避、422 直接 FAILED、作废时 HOLD 未尝试 → CANCELLED、**HOLD 超时后作废**（VOID 排队，人工重试 HOLD 后执行 VOID / resolve 后 VOID 取消）、**HOLD 明确失败后作废**（VOID 自动 CANCELLED）、关闭 409（终态含 RESOLVED_MANUALLY 可关闭）、retry 仅 FAILED 且在重试窗口内、resolve 的 400/409/422 与审计；契约测试移除 `x-planned` 后覆盖 8 种 Problem type。
- 库存：隔离接口 aud/scope 401/403、用户令牌 401、请求带库位 400、releases 的 dispositionId 与 reason 不匹配 400、通用出入库拒绝 QC-HOLD 与服务令牌、隔离量不计可用与预留、幂等重放。
- 端到端：报工不良 1 件 → `quarantined=1` → SCRAP 1 件 → `quarantined=0`、流水 `SCRAP`；冲销未处置的 NCR → `NCR_VOIDED` 扣回。

## 8. 实现拆分
1. 库存：种子库位、过滤链、三个接口、隔离量字段。
2. auth / 部署：注册 `quality-service` 客户端、init-env 生成密钥、网关拒绝 `/api/inventory/qc-hold/**`、告警与冒烟。
3. 质检：任务表、调度、令牌客户端、两个接口、关闭校验、指标。
