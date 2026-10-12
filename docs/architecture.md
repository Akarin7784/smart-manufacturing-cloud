# 架构与部署说明

## 配置层次

application.yml 放服务名和通用参数；application-local.yml 用于独立本地开发；application-cloud.yml 用于真实中间件。test profile 使用 H2 内存数据库，不连接外部服务。环境变量不要替换成本地提交的生产密码。

| 环境变量 | 默认值 / 说明 |
| --- | --- |
| SPRING_PROFILES_ACTIVE | local；部署 Alibaba 环境设置 cloud |
| SERVER_PORT | 8080 / 8081 / 8082 / 8083 / 8084（主数据） |
| NACOS_ADDR | localhost:8848，容器内为 nacos:8848 |
| NACOS_NAMESPACE | 空字符串 = public，填写 namespace ID |
| NACOS_USERNAME / NACOS_PASSWORD | 容器演示环境 Nacos 已开启鉴权，取自 .env（用户名默认 nacos，密码由 init-env 随机生成）；local profile 不连接 Nacos |
| SENTINEL_DASHBOARD | localhost:8858 |
| SENTINEL_CLIENT_PORT | 网关 9080、设备 9081、工单 9082、认证 9083、主数据 9084 |
| MYSQL_HOST / MYSQL_PORT | localhost / 3306 |
| MYSQL_USERNAME | device_app / workorder_app / auth_app / masterdata_app |
| MYSQL_PASSWORD | cloud profile 必填、无默认值：容器取 .env 的 MYSQL_APP_PASSWORD；本机 cloud 运行由 init-env.ps1 自动设置 |
| DEVICE_SERVICE_URL / WORKORDER_SERVICE_URL / MASTERDATA_SERVICE_URL | 仅 local 网关的固定目标 |
| CLIENTS_DEVICE_URL | 仅明确需要绕过发现时覆盖 Feign 目标 URL |

多实例启动时为每个 Java 进程分配唯一 SERVER_PORT 与 SENTINEL_CLIENT_PORT。跨机器注册需保证 Nacos 注册的实例地址可被网关、Feign、Sentinel 控制台访问。Nacos 客户端还要连通 9848 gRPC 端口，不能只转发 8848。

## 数据边界

设备与工单使用独立数据库和独立 MySQL 账号，没有跨库外键。示例通过 JdbcTemplate 参数绑定访问数据库。

### 数据库迁移（Flyway）

- 表结构位于各服务 `src/main/resources/db/migration`（`V<版本>__<说明>.sql`），服务启动时由 Flyway 执行；不再使用 `spring.sql.init`。已发布的 V 脚本不可修改，结构变更一律新增版本。版本号按库（服务）独立编号，每个服务各自从 V1 开始，不做跨服务统一编号。
- 演示数据位于 `db/demo`（`R__*.sql` 可重复迁移），只在 `demo` profile 中追加到 `spring.flyway.locations`；脚本必须幂等（固定主键 + `ON DUPLICATE KEY UPDATE` 或 `INSERT IGNORE`；主数据用 INSERT IGNORE，避免重放覆盖已发布的只读版本；禁止无条件 DELETE）。CI 的 `migrations twice` job 在同一库上执行两次迁移（第二次强制重放 R__），校验行数一致。
- test profile 只执行 `db/migration`（设备服务另含测试资源 `db/testdata`），不加载 `db/demo`；Testcontainers MySQL 集成测试使用同一套迁移脚本（归 MES测试与质量维护）。
- **旧库升级**：`baseline-on-migrate` 默认关闭，V1 不使用 `IF NOT EXISTS`。P2 之前由 `schema.sql` 创建的库（已有表、没有迁移历史）启动时会直接失败，不会静默跳过建表；如果跳过，V1 中新增的唯一约束（如主数据 `uk_*_current`）永远不会创建。处理方式：
  - 演示/开发环境：执行 `make demo-reset`（删除数据卷后重建）；local profile 删除 `./data` 下对应的 H2 文件。
  - 确认旧库结构与 V1 完全一致时，可设置 `FLYWAY_BASELINE_ON_MIGRATE=true`（compose 从 .env 读取），把现有库登记为版本 1，V1 不再执行。
- 工单幂等记录保留 7 天，由 `IdempotencyRecordCleaner` 每天定时按 `created_at` 升序选出最旧的一批、按主键删除（避免无序 `DELETE ... LIMIT` 在 statement binlog 下不安全）；多实例并发执行结果幂等，暂未引入 ShedLock。

### 库存服务（inventory-service）

- 独立库 `manufacturing_inventory`（账号 `inventory_app`），端口 8085，Flyway 从自己的 `V1__init.sql` 开始。数量列统一 `DECIMAL(18,6)`，接口以十进制字符串传输。
- 表：库位 `warehouse_location`、物料单位登记 `inventory_material`、批次 `lot`、现存量 `stock_on_hand`（物料+库位+批次唯一，带 version）、只追加流水 `stock_txn`、预留单 `reservation`/`reservation_line`、幂等记录 `idempotency_record`（按 client_id/sub 划分作用域，保存首次响应原文）、`outbox_event`。
- 加锁约定：需要校验可用量的写操作对物料全部现存量行按 id 升序 `SELECT ... FOR UPDATE`，多物料按编码升序；可用量 = 现存量 − ACTIVE 预留未扣减量。死锁牺牲者整笔重试（最多 3 次）。
- 安全：`/inventory/reservations/**` 只接受 aud=`inventory-service` 且 scope 含 `inventory:reserve` 的服务令牌；其他接口使用用户令牌（`inventory:read` / `inventory:write`）。错误统一为 RFC 7807。接口契约见 `docs/api/inventory-openapi.yaml`。
- 镜像 JVM 参数来自 `inventory-service/jvm.options`（`-Xmx256m`），Dockerfile 对没有该文件的模块使用空参数文件。

已实现账号登录、管理员用户管理及三种角色的接口授权。示例不提供完整业务 CRUD、分页、租户数据权限或 MES 生产排程。这些属于后续业务模块，而不是基础服务治理框架已经完成的功能。

## Sentinel 规则持久化

当前控制台可以动态推送规则，但规则存放在客户端内存。生产可添加 com.alibaba.csp:sentinel-datasource-nacos 依赖，配置 spring.cloud.sentinel.datasource.<name>.nacos 的 server-addr、group-id、data-id、data-type=json、rule-type。普通 HTTP/Feign 使用 flow / degrade；网关使用 gw-flow / gw-api-group。

配置中心保存规则 JSON，客户端订阅生效。原版控制台推送规则不会自动写入 Nacos，需改造控制台规则仓库或以 Nacos 配置发布作为规则管理入口。先在测试环境验证规则资源名称与 QPS，再启用生产规则。

## RocketMQ 扩展

演示环境已接入 RocketMQ 5.5.0（namesrv + broker，随 `make demo` 启动），Topic、Tag 与地址见 [messaging.md](messaging.md)。

建议工单模块采用本地事务写入工单与 outbox 事件，再异步发布 WorkOrderCreated。消息包含 eventId / orderId；消费者按 eventId 幂等，重试与死信队列可观测。不要简单地“写库后发消息”并把两个动作宣称为原子操作。

按官方兼容表，本版本对应 RocketMQ 5.3.1。接入时核对 Spring Cloud Stream RocketMQ 或 RocketMQ Spring 的兼容版本，部署 NameServer / Broker，明确 ACL、Topic、Consumer Group、超时和失败策略。当前工程未引入消息客户端、Broker 或事件发布，避免把未使用的组件作为启动依赖。

## Seata 扩展

当前业务只有工单库写入，本地单语句事务已足够。若后续一个同步流程需要跨服务扣库存、扣账户等，可引入本版本对应的 Seata 2.5.0。

接入必须一起实现：服务端事务存储、注册配置、业务 undo_log、数据源代理、事务组映射以及全局事务入口；否则只加 @GlobalTransactional 并不能保证跨服务原子性。先比较 AT / TCC / Saga 与业务补偿需求，再决定方案。资源占用操作还需幂等键，读设备状态不能替代资源预留。

## 上线前的具体工作

1. 已使用 Spring Security JWT Resource Server 与业务服务独立鉴权；按实际部署需求对接企业 OAuth2/OIDC 单点登录及数据权限。
2. 将 Nacos 改为鉴权开启的多节点部署；启用配置访问隔离与 namespace 分环境。
3. 替换 Compose 开发密码，使用环境注入或密钥管理服务；控制台限制内网访问。
4. 用迁移工具管理表结构，启用连接池与慢 SQL 指标、数据库备份。
5. 接入 Micrometer Tracing / OpenTelemetry，设置 metrics 告警与错误日志检索。
6. 配置连接超时、请求体限制、持久化限流规则，以及容量与故障演练。
7. 根据官方安全公告升级同一兼容版本线并执行全量测试。

## 故障定位

- Nacos 启动慢：查看 docker compose logs nacos，确认 memory 足够、8848 与 9848 端口正常。
- MySQL 认证失败：初始化 SQL 仅在空数据卷首次启动执行，修改脚本不会自动改变已有用户。
- 网关 503：检查 Nacos MANUFACTURING 分组是否有在线实例；禁止本机和容器注册混用不可达地址。
- Feign 503：看设备服务状态、数据库连接与请求 ID 日志；降级不会返回伪造成功。
- Sentinel 控制台无资源：确认 cloud profile、控制台连通、已有接口请求，客户端端口未冲突。
- local 数据：保存在运行工作目录的 data/；脚本固定工作目录为项目根。

## 认证数据与密钥边界

认证服务维护独立 manufacturing_auth 数据库，只存储 BCrypt 密码散列。RSA 私钥只由认证服务持有，网关和业务服务通过 JWKS 公钥校验令牌。Feign 调用从已验证的安全上下文传播 Bearer 令牌。角色与权限是短期 JWT 的签发快照；账号变更不立即吊销已有令牌。详见 authentication.md。

## 服务身份（OAuth2 client_credentials）

服务间调用（如工单服务调用库存服务预留物料、续期定时任务、补偿释放）没有用户 JWT，统一使用服务身份：

- **令牌端点**：认证服务 `POST /auth/oauth2/token`（经网关为 `/api/auth/oauth2/token`），按 RFC 6749 4.4 实现，仅支持 `grant_type=client_credentials`。客户端凭据优先用 HTTP Basic，也接受表单 `client_id`/`client_secret`（二者不能同时出现）。可选 `scope` 必须是该客户端允许范围的子集，省略时签发全部允许值。成功响应 `{access_token, token_type: "Bearer", expires_in, scope}` 且 `Cache-Control: no-store`；错误按 RFC 6749 5.2 返回 `{error, error_description}`：凭据错误或客户端不存在统一 401 `invalid_client`（带 `WWW-Authenticate: Basic`），`invalid_request`/`unsupported_grant_type`/`invalid_scope` 为 400。
- **服务间直连**：容器内工单服务通过 Nacos 发现直接调用 `auth-service`，不经过网关；网关入口免 JWT，主要用于冒烟测试和外部集成。生产环境应对该入口配置 Sentinel 限流（BCrypt 校验每次约数十毫秒，防止被用作暴力破解或 CPU 消耗入口）。
- **令牌声明**：与用户令牌使用同一把 RSA 私钥签名，资源服务继续用 `/auth/jwks` 验签。声明为 `iss`、`sub=client_id`、`client_id`、`scope`（空格分隔）、`aud`（按客户端配置，工单客户端为 `inventory-service`）、`iat`/`nbf`/`exp`（默认 5 分钟，`AUTH_CLIENT_TOKEN_TTL_SECONDS` 可调，范围 60–600 秒）、`jti`，以及 `token_type=service`。调用方缓存令牌，临近过期再刷新。
- **与用户令牌的区分**：服务令牌不含 `username`/`roles`/`permissions`，受众不是 `manufacturing-api`，因此网关和现有业务服务（按 `manufacturing-api` 校验受众）会直接拒绝它，不能冒充用户。接受服务令牌的资源服务（库存服务）须：校验 `aud` 包含自身服务名、`token_type == service`、`scope` 包含所需权限（如 `inventory:reserve`）；幂等作用域按 `client_id` 划分。需要用户身份的接口不得接受 `token_type=service` 的令牌。
- **客户端注册表**：`auth-service` 的 `manufacturing.auth.clients[]`（client-id、secret-hash、scopes、audience）。目前只注册 `workorder-service`（scope `inventory:reserve`，audience `inventory-service`）。认证服务**只保存密钥的 BCrypt 散列**，格式不是 BCrypt 时拒绝启动；cloud profile 设置 `manufacturing.auth.require-secrets=true`，缺少散列时启动失败；local/test 缺少散列时仅禁用该客户端。未知客户端也执行一次假散列比对，避免通过耗时枚举客户端。
- **密钥分发**：`scripts/init-env.sh` / `init-env.ps1` 在被 Git 忽略的 `.env` 中生成随机明文 `WORKORDER_CLIENT_SECRET`（只注入 workorder 容器）及其 BCrypt 散列 `WORKORDER_CLIENT_SECRET_HASH`（只注入 auth 容器，单引号包裹避免 compose 插值 `$`）。BCrypt 依次使用 htpasswd、python3 bcrypt 或一次性 `httpd:2.4-alpine` 容器生成。明文与散列都不写入仓库或 Nacos。轮换方法：删除 `.env` 中这两行后重新运行 init-env，并重启 auth 与 workorder。
