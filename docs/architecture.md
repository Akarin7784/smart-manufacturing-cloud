# 架构与部署说明

## 配置层次

application.yml 放服务名和通用参数；application-local.yml 用于独立本地开发；application-cloud.yml 用于真实中间件。test profile 使用 H2 内存数据库，不连接外部服务。环境变量不要替换成本地提交的生产密码。

| 环境变量 | 默认值 / 说明 |
| --- | --- |
| SPRING_PROFILES_ACTIVE | local；部署 Alibaba 环境设置 cloud |
| SERVER_PORT | 8080 / 8081 / 8082 / 8083 |
| NACOS_ADDR | localhost:8848，容器内为 nacos:8848 |
| NACOS_NAMESPACE | 空字符串 = public，填写 namespace ID |
| NACOS_USERNAME / NACOS_PASSWORD | 默认空，开发 Nacos 关闭鉴权；启用鉴权时设置 |
| SENTINEL_DASHBOARD | localhost:8858 |
| SENTINEL_CLIENT_PORT | 网关 9080、设备 9081、工单 9082、认证 9083 |
| MYSQL_HOST / MYSQL_PORT | localhost / 3306 |
| MYSQL_USERNAME | device_app / workorder_app / auth_app |
| MYSQL_PASSWORD | local-demo-password |
| DEVICE_SERVICE_URL / WORKORDER_SERVICE_URL | 仅 local 网关的固定目标 |
| CLIENTS_DEVICE_URL | 仅明确需要绕过发现时覆盖 Feign 目标 URL |

多实例启动时为每个 Java 进程分配唯一 SERVER_PORT 与 SENTINEL_CLIENT_PORT。跨机器注册需保证 Nacos 注册的实例地址可被网关、Feign、Sentinel 控制台访问。Nacos 客户端还要连通 9848 gRPC 端口，不能只转发 8848。

## 数据边界

设备与工单使用独立数据库和独立 MySQL 账号，没有跨库外键。示例通过 JdbcTemplate 参数绑定访问数据库。schema.sql 是开发初始化脚本，并非生产数据库迁移工具。上线时改为 Flyway/Liquibase，并关闭 spring.sql.init.mode=always。

已实现账号登录、管理员用户管理及三种角色的接口授权。示例不提供完整业务 CRUD、分页、租户数据权限或 MES 生产排程。这些属于后续业务模块，而不是基础服务治理框架已经完成的功能。

## Sentinel 规则持久化

当前控制台可以动态推送规则，但规则存放在客户端内存。生产可添加 com.alibaba.csp:sentinel-datasource-nacos 依赖，配置 spring.cloud.sentinel.datasource.<name>.nacos 的 server-addr、group-id、data-id、data-type=json、rule-type。普通 HTTP/Feign 使用 flow / degrade；网关使用 gw-flow / gw-api-group。

配置中心保存规则 JSON，客户端订阅生效。原版控制台推送规则不会自动写入 Nacos，需改造控制台规则仓库或以 Nacos 配置发布作为规则管理入口。先在测试环境验证规则资源名称与 QPS，再启用生产规则。

## RocketMQ 扩展

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
