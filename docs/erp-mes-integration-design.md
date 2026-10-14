# ERP ↔ MES 集成设计（M2，契约先行）

> 范围：只有契约和设计，**不包含实现代码**。实现 PR 等 PM 审查本契约、ERP 侧补齐或确认 `docs/integration` 之后再开。
> 契约：`docs/api/erp-integration-openapi.yaml`。
> 前提：PM 决定不做 ERP-MES 端到端联调（#73 搁置），双方按 API 契约对接。

## 0. ERP 源契约核查（2026-10-14）
| 项 | 结论 |
| --- | --- |
| 仓库 | `Akarin7784/smart-manufacturing-erp`，**公开**仓库（不是私有），main `29af6c5` |
| `docs/integration/` | **不存在**；仓库里也没有 `docs/` 目录，没有任何 OpenAPI 文件 |
| 生产订单 / MES 接口 | **不存在**。ERP 现有模块：mdm、purchase、sales、inventory、finance、workflow、auth |
| 错误体 | `R{code:int, msg, data}`，HTTP 状态取 ErrorCode 大类（如 409 / 40902），**不是** problem+json |
| 幂等 | 请求头 `Idempotency-Key`；`UNIQUE(user_name, idem_key)`，保留 24h；同键不同参数返回 409/40902，处理中返回 409/40904，成功则返回原单据 |
| 认证 | ERP 自签 JWT（`ERP_JWT_SECRET`，用户登录 + `tv` 版本号），**没有** client_credentials / 服务账号 |

所以本 PR 里的路径、字段、scope、回调地址**都是 MES 的提案**，不能当作 ERP 已有的行为。冲突项和缺失项见 §5，需要 ERP 方确认或补契约。

## 1. ERP → MES：生产订单下达 → 创建工单
- `POST /api/integration/erp/work-orders/dispatch`，必须带 `Idempotency-Key`。
- 首次成功返回 201。同键同请求体重放返回 200，body 与首次相同，并带 `Idempotent-Replayed: true`。同键不同请求体返回 422 `idempotency-key-conflict`；缺幂等键返回 400 `idempotency-key-required`。
- 只创建工单（`CREATED`），不自动下达。下达仍由 MES 计划员执行，或另起契约。
- `erpProductionOrderNo` 在 MES 侧唯一：已绑定其他工单、且幂等键不同时，返回 409 `erp-production-order-conflict`。

### 字段映射（MES 提案 vs ERP 契约）
| 下达请求字段 | MES 工单字段 | ERP 契约 | 说明 |
| --- | --- | --- | --- |
| `erpProductionOrderNo` | 新增 `erp_order_no` 字段（实现 PR 中加 Flyway 迁移） | 无，**待 ERP 定义** | 业务主键 |
| `productCode` | `product` | 无 | 必须存在于 MES 主数据，否则返回 422 `release-masterdata-invalid` |
| `quantity`（Decimal 字符串） | `quantity`（目前是整数） | 无 | 精度冲突，见 OQ-MAP-2 |
| `uom` | 无对应字段 | 无 | 新增字段或校验，见 OQ-MAP-3 |
| `plannedStartDate` / `plannedFinishDate` | 无 | 无 | 实现时决定是否落库 |
| `warehouseCode` | 无 | ERP 有仓库主数据 | 用作完工入库的默认仓 |
| `deviceId`（可选） | `deviceId`（**MES 必填**） | ERP 没有这个概念 | 冲突，见 OQ-MAP-1 |

## 2. MES → ERP 回调：领料 / 完工入库 / 报废
| 动作 | 提案路径（宿主在 ERP） | 幂等请求号规则（提案） |
| --- | --- | --- |
| 领料 | `POST /api/integration/mes/material-issues` | `MI-{工单号}-{序号}` |
| 完工入库 | `POST /api/integration/mes/finished-goods-receipts` | `FGR-{工单号}-{序号}` |
| 报废 | `POST /api/integration/mes/scraps` | `SCR-{工单号}-{工序}-{序号}` |

- **幂等**：请求体中的 `requestNo` 全局唯一。相同 `requestNo` 加相同内容，ERP 返回与首次相同的结果（200，带同一个 `erpDocumentNo`）；相同 `requestNo` 加不同内容返回 422（或 ERP 的 409/40902，见 OQ-ERR-1）。
- **重试**：MES 用 outbox 和后台任务（参照现有 `ReservationReleaseWorker` 模式）至少一次投递。
  - 网络错误、超时、5xx、429：指数退避重试，退避间隔和上限在实现 PR 里定，并列入 observability。
  - 401：刷新服务令牌后重试一次。
  - 400、403、409、422：不重试，标记 FAILED 并告警，等人工处理。
- **重复返回相同结果**：ERP 必须保证这一点。现有 ERP 幂等记录只保留 24h，过期后会被当作新键处理，与 MES 的长期重试冲突，见 OQ-IDEM-1。

## 3. 服务账号与 JWT
| 方向 | 签发方 | client / scope / audience（提案） |
| --- | --- | --- |
| ERP → MES | MES `auth-service`，`POST /api/auth/oauth2/token`，`grant_type=client_credentials` | client `erp-integration`，scope `integration:workorder-dispatch`，aud `manufacturing-api` |
| MES → ERP | **待 ERP 定义**（ERP 目前没有服务账号） | 提案 scope：`integration:material-issue`、`integration:finished-goods-receipt`、`integration:scrap` |

- 沿用 `docs/authentication.md` 的规则：一个令牌只有一个受众；服务令牌不带 `permissions`；服务 scope 不进 `Role` 枚举。
- 受众不符返回 401 `authentication-required`，scope 不符返回 403 `access-denied`，和库存、质检服务一致。
- **密钥只能来自环境变量或 Nacos**，例如 `ERP_INTEGRATION_CLIENT_SECRET`（明文只给 ERP），auth 端只保存 BCrypt 散列；MES→ERP 的凭据例如 `MES_ERP_CLIENT_SECRET`。本仓库是公开仓库，**禁止**提交任何密钥、令牌或 ERP 服务账号的 JWT。

## 4. 错误
- MES 暴露的接口：`application/problem+json`，`type` 用 kebab-case 相对 URI，与 workorder、masterdata 一致。type 表见 OpenAPI 中的 `ProblemType`。
- ERP 回调的错误：ERP 目前返回 `R{code,msg}`。MES 适配层只按 HTTP 状态和 ERP 数字码映射成 MES 内部的 problem type，不解析 `msg`。映射表需要 ERP 先提供（OQ-ERR-1）。契约 examples 按"ERP 改成 problem+json"写，这一点也需要 ERP 确认。

## 5. 开放问题 / 需要 ERP 侧改动
| ID | 问题 | 需要谁 |
| --- | --- | --- |
| OQ-SRC-1 | ERP 仓库没有 `docs/integration`，需要 ERP 新建并作为唯一事实来源；本 PR 的路径和字段以那边的定稿为准 | ERP |
| OQ-AUTH-1 | ERP→MES 是否接受由 MES auth-service 签发服务令牌（客户端 `erp-integration`），以及 scope 和 aud 的名称 | PM / ERP |
| OQ-AUTH-2 | MES→ERP：ERP 没有 client_credentials，需要 ERP 新增服务账号机制，或接受 MES 令牌（JWKS 验签） | ERP |
| OQ-ERR-1 | ERP 错误体是 `R` 数字码，不是 problem+json。ERP 是否改成 problem+json，否则要给出数字码到 type 的映射表 | ERP |
| OQ-IDEM-1 | ERP 幂等按用户维度、只保留 24h，过期后同键会重复建单。回调需要按 `requestNo` 永久（或足够长时间）去重 | ERP |
| OQ-IDEM-2 | 幂等键放在请求头 `Idempotency-Key`（ERP 现有做法）还是请求体 `requestNo`（本提案）；回调方向需要统一 | ERP |
| OQ-CB-1/2/3 | 领料、完工入库、报废接口在 ERP 中不存在：路径、字段、是否部分成功、单据号格式 | ERP |
| OQ-MAP-1 | MES 创建工单时 `deviceId` 必填，且要求设备在线；ERP 没有设备概念。方案二选一：下达时不校验设备，或在 MES 侧改为下达后分配设备 | PM |
| OQ-MAP-2 | MES `quantity` 是整数，ERP 和本契约用 6 位小数；需要定精度和舍入规则 | PM / ERP |
| OQ-MAP-3 | `uom` 和仓库编码在两边主数据中的映射，以及编码是否一致 | ERP / MES |
| OQ-FLOW-1 | 领料时机：按派工、报工倒冲还是领料单？完工入库在 COMPLETED 时触发还是 CLOSED 时触发？与 #72 退料的先后顺序 | PM |

## 6. 测试约定
OpenAPI 中每个操作都带完整 examples：成功、幂等重放、401（缺令牌 / 坏令牌 / 受众不符）、403（scope 不符）、业务 409/422，全部是带顶层 `type` 的 problem+json。测试工程师可以直接据此生成 mock 和契约测试。实现 PR 中计划增加 `ErpIntegrationContractTest`，校验 examples 与 schema 一致。
