# ERP ↔ MES 集成设计（M2，契约先行）

> 范围：只有契约和设计，**不包含实现代码**。实现 PR 等 PM 审查本契约、ERP 侧补齐或确认 `docs/integration` 之后再开。
> 契约：`docs/api/erp-integration-openapi.yaml`。
> 前提：PM 决定不做 ERP-MES 端到端联调（#73 搁置），双方按 API 契约对接。

## 0. ERP 源契约核查（2026-10-14）
| 项 | 结论 |
| --- | --- |
| 仓库 | `Akarin7784/smart-manufacturing-erp`（公开），核查时 main `29af6c5` |
| `docs/integration/` | **不存在**；仓库里也没有 `docs/` 目录，没有任何 OpenAPI 文件 |
| 生产订单 / MES 接口 | **不存在**。ERP 现有模块：mdm、purchase、sales、inventory、finance、workflow、auth |
| 错误体 | `R{code:int, msg, data}`，HTTP 状态取 ErrorCode 大类（如 409 / 40902） |
| 幂等（现状） | 请求头 `Idempotency-Key`；`UNIQUE(user_name, idem_key)`，保留 24h |
| 认证 | ERP 自签 JWT，**没有** client_credentials / 服务账号 |

本 PR 里的路径和字段是 MES 提案；与 ERP 最终契约冲突时以 ERP `docs/integration` 为准。下面「已拍板」条款是 PM 与 ERP 约定的边界，写进契约后两边实现都按它来。

## 1. 已拍板（覆盖此前不一致的说法）

### 1.1 字段与数量
| 项 | 决定 |
| --- | --- |
| `deviceId` | 可选。创建时**不**校验设备是否存在或在线。 |
| 数量 | 与 ERP `Decimal6` 一致：十进制字符串，**最多 6 位小数**，不要求补零（`"10"`、`"10.5"` 合法）。超过 6 位 → **400**，不四舍五入、不截断。两个方向相同。 |

### 1.2 幂等
| 方向 | 规则 |
| --- | --- |
| ERP→MES 下达 | 只用请求头 `Idempotency-Key`。同键不同请求体 → **409** `idempotency-key-conflict`（与 ERP 回调的 40902 对称；工单服务既有接口仍是 422，本集成接口单独按此约定）。 |
| MES→ERP 回调 | 请求头 `Idempotency-Key` **必须等于**请求体 `requestNo`。ERP 按**请求头**去重，按**服务身份**隔离，记录保留 **≥ 90 天**（不是用户维度 24h）。缺头、缺 `requestNo` 或两者不一致 → 400 / 400（与 ERP PR #25 一致）；同键不同参数 → 409 / 40902；处理中 → 409 / 40904。 |

### 1.3 业务时机
| 动作 | 时机 |
| --- | --- |
| 领料回传 | **报工倒冲**：报工成功后立即回传，一次报工一笔 |
| 报废回传 | 报废一产生就立即回传（报工报废数 > 0，或 NCR 报废处置）；`warehouseCode` 必填；`reasonCode` 由 ERP 按报废原因字典校验，不在字典 → 400 / 400，契约不写死枚举 |
| 完工入库 | 工单变为 **COMPLETED** 时（不是 CLOSED）。未 COMPLETED 就回传 → ERP 409 / 40903 |
| 退料（#72） | 必须在工单 **CLOSED** 之前完成 |

### 1.4 错误体
| 方向 | 错误体 |
| --- | --- |
| ERP→MES（MES 返回） | `application/problem+json`，顶层 `type`，写法和 workorder / masterdata 一致 |
| MES→ERP（ERP 返回） | 过渡期保持 `R{code,msg,data}`；OpenAPI 的错误 examples **只用 R**，码表见 `ErpErrorR`。MES→ERP **不**写 problem+json 错误样例 |

两边一致：令牌缺失 / 无效 / 过期 / **aud 不符 → 401**；令牌有效但**身份或 scope 不对 → 403**（与 ERP #25 一致）。

## 2. ERP → MES：生产订单下达 → 创建工单
- `POST /api/integration/erp/work-orders/dispatch`
- 首次成功 201；同键同请求体重放 200 + `Idempotent-Replayed: true`，body 与首次相同
- `erpProductionOrderNo` 在 MES 侧唯一；已绑定其他工单且幂等键不同 → 409 `erp-production-order-conflict`

### 字段映射
| 下达请求字段 | MES 工单字段 | ERP 契约 | 说明 |
| --- | --- | --- | --- |
| `erpProductionOrderNo` | 新增 `erp_order_no`（实现 PR 加 Flyway） | 无，待 ERP 定义 | 业务主键 |
| `productCode` | `product` | 无 | 须存在于 MES 主数据，否则 422 |
| `quantity`（最多 6 位小数字符串） | `quantity`（目前整数） | ERP `Decimal6` | **已拍板**：最多 6 位小数；实现 PR 改类型 |
| `uom` | 无 | 无 | 新增或校验，见 OQ-MAP-3 |
| `warehouseCode` | 无 | ERP 有仓库 | 完工入库默认仓 |
| `deviceId`（可选） | 可选写入 | 无设备概念 | **已拍板**：可选，不校验在线 |

## 3. MES → ERP 回调
| 动作 | 提案路径 | 触发 | 幂等请求号建议 |
| --- | --- | --- | --- |
| 领料（报工倒冲） | `POST /api/integration/mes/material-issues` | 报工成功后立即 | `MI-{工单}-{工序}-{报工ID}` |
| 完工入库 | `POST /api/integration/mes/finished-goods-receipts` | 工单 COMPLETED | `FGR-{工单}` |
| 报废 | `POST /api/integration/mes/scraps` | 报废产生即回传 | `SCR-{工单}-{工序}-{报工或NCR}` |

- 请求头 `Idempotency-Key` = 请求体 `requestNo`
- `requestNo` / `Idempotency-Key` 字符集与 ERP #25 一致：`^[A-Za-z0-9._:-]+$`，最长 64
- 库存不足：409 / 40906（领料、报废）
- 重试：网络 / 5xx / 429 / 40904 退避；40906 不重试；401 刷新令牌后重试一次；400 / 403 / 40902 / 40903 不重试，转人工
- 成功体为 ERP 的 `R{code:0,msg,data}`，`data = {requestNo, erpDocumentNo, replayed, acceptedAt}`；重放 200 + `Idempotent-Replayed: true` + `replayed=true`，`erpDocumentNo`、`acceptedAt` 与首次相同
- 原则（PM 拍板）：打到 ERP 的回调以 ERP `docs/integration`（ERP #25）为准，MES 对齐

## 4. 服务账号与 JWT
| 方向 | 签发方 | client / scope / audience（提案） |
| --- | --- | --- |
| ERP → MES | MES `auth-service` `client_credentials` | `erp-integration` / `integration:workorder-dispatch` / `manufacturing-api` |
| MES → ERP | ERP 签发（ERP #25，client `mes-integration`，aud `erp-api`） | `erp:integration:material-issue`、`erp:integration:finished-goods-receipt`、`erp:integration:scrap` |

密钥只从环境变量或 Nacos 读取（如 `ERP_INTEGRATION_CLIENT_SECRET`），本公开仓库不提交任何密钥。

## 5. 开放问题（已关闭的打钩）
| ID | 状态 | 说明 |
| --- | --- | --- |
| ~~OQ-MAP-1~~ | **已关闭** | deviceId 可选，创建不校验在线 |
| ~~OQ-MAP-2~~ | **已关闭** | 数量最多 6 位小数（ERP Decimal6）；超过 6 位 400 |
| ~~OQ-IDEM-2~~ | **已关闭** | ERP→MES 用请求头；MES→ERP 请求头 = requestNo |
| ~~OQ-IDEM-1~~ | **已关闭（约定）** | 回调按服务身份去重，TTL ≥ 90 天；ERP 需改实现 |
| ~~OQ-FLOW-1~~ | **已关闭** | 报工倒冲 / COMPLETED 入库 / 报废即时 / 退料在 CLOSED 前 |
| ~~OQ-ERR-1（部分）~~ | **已关闭（过渡）** | MES→ERP 保持 R；码表写入契约。ERP 是否最终改 problem+json 另议 |
| OQ-SRC-1 | 仍开 | ERP 需新建 `docs/integration` 作为唯一事实来源 |
| OQ-AUTH-1 | 仍开 | ERP→MES 是否接受 MES auth 签发的服务令牌及命名 |
| ~~OQ-AUTH-2~~ | **已关闭（方案）** | ERP #25 定为 ERP 自有服务账号 `mes-integration`；待 ERP 实现 |
| OQ-CB-1/2/3 | 仍开 | 三个回调在 ERP 中尚不存在：路径、字段、冲销报工反向领料 |
| OQ-MAP-3 | 仍开 | uom、仓库编码两边主数据映射 |
| ~~OQ-ERR-2~~ | **已关闭** | 按 ERP PR #25 统一 400 / 400，不新增细分码 |
| ~~OQ-ALIGN-1~~ | **已关闭** | 回调以 ERP #25 为准：aud 不符 401、scope `erp:integration:*`、成功体 `R{code:0,msg,data}`、领料字段照 #25 |
| OQ-CB-4 | 仍开（小钱的 ERP 后续文档 PR） | 领料倒冲需要的 `operationNo`、`reportId`（追溯到工序 / 报工）在 ERP #25 中没有，MES 已从请求中去掉；请 ERP 补字段 |
| OQ-CB-5 | 仍开（小钱） | ERP #25 的 `reasonCode` 仍写死枚举，按 PM 决定由小钱在后续文档 PR 中去掉，改为字典校验 |
| ~~OQ-DEC-1~~ | **已关闭** | 两个方向统一采用 ERP `Decimal6`：最多 6 位小数，不要求补零 |

## 6. 测试约定
OpenAPI 每个操作都有完整 examples：成功、幂等重放、401、403、400（含幂等键缺失 / 不一致、超 6 位小数、报废原因不在字典）、409（含 40902 / 40903 / 40904）。
- ERP→MES：错误 examples 为 problem+json
- MES→ERP：错误 examples 仅为 `R{code,msg,data}`
测试工程师可据此生成 mock 与契约测试（含头与 requestNo 不一致用例）。
