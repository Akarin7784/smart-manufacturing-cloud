# smart-manufacturing-cloud 代码质量体检报告

> 体检基线：`main` @ `f230c6f`（Merge PR #1 codex/auth-authorization）；体检日期 2026-10-12；执行人：MES测试与质量。
> 本报告所有数字均来自本地构建日志与 JaCoCo 报告，未做估算。

## 1. 构建与测试结果

### 1.1 执行命令

本机未安装 JDK，使用官方 Maven 镜像在容器中执行（Maven 本地仓库缓存挂载复用）：

```bash
docker run --rm -v "$PWD":/src -v <m2缓存>:/root/.m2 -w /src maven:3.9-eclipse-temurin-21 \
  mvn -B -ntp org.jacoco:jacoco-maven-plugin:0.8.12:prepare-agent verify org.jacoco:jacoco-maven-plugin:0.8.12:report
```

> 仓库 `pom.xml` 当前未声明 JaCoCo 插件，以上为命令行临时挂载，未修改任何业务代码或构建配置。

### 1.2 结果

- 结果：**BUILD SUCCESS**，全部 8 个 reactor 模块通过，总耗时 1 分 11 秒。
- 测试：**33 个用例，0 失败，0 错误，0 跳过**。

| 模块 | 测试类 | 用例数 | 结果 |
|---|---|---|---|
| cloud-security | SecurityClaimsTest | 3 | 通过 |
| auth-service | AuthIntegrationTest | 10 | 通过 |
| cloud-gateway | GatewayRoutingTest | 7 | 通过 |
| device-service | DeviceIntegrationTest | 5 | 通过 |
| workorder-service | WorkOrderIntegrationTest | 8 | 通过 |
| cloud-api / cloud-common | 无测试 | 0 | — |

### 1.3 覆盖率（JaCoCo 0.8.12，按模块自身类统计）

| 模块 | 行覆盖 | 分支覆盖 |
|---|---|---|
| cloud-security | 13/13 = **100.0%** | 5/6 = **83.3%** |
| auth-service | 116/122 = **95.1%** | 34/48 = **70.8%** |
| cloud-gateway | 43/48 = **89.6%** | 6/6 = **100.0%** |
| workorder-service | 29/36 = **80.6%** | 11/18 = **61.1%** |
| device-service | 8/12 = **66.7%** | 2/2 = **100.0%** |
| cloud-common | 无报告（模块无测试，未生成 jacoco.exec） | — |
| cloud-api | 无报告（仅 record 契约，无测试） | — |

说明：
- cloud-common 的异常处理、RequestId 过滤器、资源服务安全配置虽在各业务服务的集成测试中被间接执行，但 JaCoCo 单模块报告不统计跨模块类，因此**目前没有任何报告能证明 cloud-common 的覆盖情况**。建议增加 `report-aggregate` 汇总模块。
- 当前代码规模很小（main 代码约 815 行），覆盖率高主要因为业务逻辑少；随领域服务落地，必须用门禁防止回落。

### 1.4 总体评价

骨架质量明显高于一般样板工程：安全链（RS256 + JWKS、网关与服务双重校验、`denyAll` 兜底、登录行锁计数、防账号枚举的 dummy hash）、中文注释、CI 固定 Action SHA 等都做得规范。主要短板集中在：**测试只跑 H2、没有真正的数据库/中间件集成测试；数据库无版本化迁移；工单创建缺幂等与并发控制；领域模型尚是字符串 + JdbcTemplate 直写**。

## 2. 问题清单（按严重程度排序）

> 当前没有“严重”级（可直接导致安全漏洞或数据损坏上线）问题。

### 高

| # | 位置 | 问题 | 风险 | 修复建议 |
|---|---|---|---|---|
| H1 | `workorder-service/.../WorkOrderService.java:15-27`；`WorkOrderController.java:15-19` | 创建工单无幂等键：每次 POST 生成新 UUID，客户端超时重试/网关重试会产生重复工单 | 生产现场重复下单、重复领料，是 MES 验收必问点 | 引入 `Idempotency-Key` 请求头或业务单号（如 `order_no`）+ 唯一索引；重复请求返回首次结果 |
| H2 | `WorkOrderService.java:16-25` | 设备状态“先查后写”（check-then-act），跨服务无预留；且 `create` 无 `@Transactional`，后续加多表写入时没有事务边界 | 设备在查询后转为离线/被占用，工单仍创建；后续扩展多表时出现部分写入 | 短期：在 Service 层明确 `@Transactional`；中期：设备产能“预留→确认/释放”+ 补偿（AGENTS.md 已记录此约束），或采用 Outbox 事件 |
| H3 | `*/src/main/resources/schema.sql`；`workorder-service/src/main/resources/application-cloud.yml:28-30`（其他服务同） | 表结构由 `spring.sql.init.mode: always` 每次启动执行，无 Flyway 版本化迁移；种子数据（D001/D002）混在 schema 中 | 无法演进表结构（加列/索引），生产环境启动即写数据；测试与演示数据无法隔离 | 改为 Flyway `db/migration`（V1__baseline.sql），演示数据放 `db/demo` 独立 location，仅 demo profile 加载（与运维 P2 对齐） |
| H4 | `*/src/test/resources/application-test.yml`（auth/device/workorder 均为 `jdbc:h2:mem:...;MODE=MySQL`） | 全部数据库测试跑在 H2 MySQL 兼容模式，无真实 MySQL / Nacos 集成测试 | `SELECT ... FOR UPDATE`（`AuthUserService.java:33`）锁语义、字符集、时区、唯一键异常翻译在 H2 与 MySQL 行为不同，测试绿≠生产正确 | 引入 Testcontainers（MySQL 8 + Nacos），见第 3 节 |
| H5 | `cloud-common`（无 `src/test`）；根 `pom.xml` 无 JaCoCo 配置；`.github/workflows/build.yml` | 公共模块零测试；CI 不统计覆盖率、无门禁 | 公共组件（异常映射、安全链）回归无人发现；覆盖率可无声下滑 | cloud-common 补单测；pom 引入 JaCoCo + `check` 规则；CI 上传报告并设门禁（见 3.6） |

### 中

| # | 位置 | 问题 | 风险 | 修复建议 |
|---|---|---|---|---|
| M1 | `WorkOrderService.java:20-22`；`DeviceController.java:24` | 设备/工单状态均为裸字符串（`"ONLINE"`、`"CREATED"`），无枚举与状态机 | 拼写错误编译期不可见；工单后续状态（下达/开工/完工/关闭）无迁移约束 | 定义 `WorkOrderStatus` / `DeviceStatus` 枚举与领域状态机，非法迁移抛业务异常并有单测 |
| M2 | `workorder-service/src/main/resources/schema.sql:2-8` | `work_order` 无乐观锁 `version`、无 `created_at/created_by` 审计字段、`device_id` 无索引 | 后续报工/状态变更并发覆盖（丢失更新）；无法追溯 | 加 `version` + 条件更新；加审计字段与索引 |
| M3 | `WorkOrderService.java:29-31`；`DeviceController.java:22-25`；`AuthUserService.java:71-73` | 列表接口无分页，工单按 UUID `id` 排序（无业务意义） | 数据量增长后全表扫描与大响应；演示时排序混乱 | 统一分页参数与 `PageResponse` 契约；按创建时间排序 |
| M4 | `DeviceController.java:15-35` | Controller 直接持有 `JdbcTemplate` 写 SQL，无 Service/Repository 分层；`@RefreshScope` 加在 Controller 上 | 分层不清，不符合“分层干净”的验收标准；RefreshScope 代理 Controller 有副作用 | 拆出 `DeviceService` + `DeviceRepository`；配置项用 `@ConfigurationProperties` |
| M5 | `cloud-common/.../ApiExceptionHandler.java:37-41` | 只覆盖少数异常，`HttpRequestMethodNotSupportedException`、`HttpMediaTypeNotSupportedException`、`MissingServletRequestParameterException`、`NoResourceFoundException` 等落入通用分支返回 500 | 客户端错误被报成服务端 500，污染告警与 SLO | 继承 `ResponseEntityExceptionHandler` 或补充对应处理并保留 4xx 状态；加单测 |
| M6 | `workorder-service/.../DeviceFallbackFactory.java:13-24` | 除 401/403/404 外，下游 400/409/422 也一律映射为 503 | 下游参数错误被误报为“服务不可用”，排障困难 | 4xx 原样映射为对应业务错误，仅超时/5xx/熔断视为不可用 |
| M7 | `cloud-gateway/.../GatewaySecurityConfiguration.java:38`；`AuthUserService.java:44-46` | 登录接口无网关级限流；账号 5 次失败锁 15 分钟可被他人利用锁定任意已知账号 | 暴力破解与针对管理员的锁定型 DoS | 网关按 IP 限流（Sentinel 网关流控或 RequestRateLimiter），锁定策略结合 IP 维度 |
| M8 | `workorder-service/src/main/resources/application-cloud.yml:24`（其他服务同） | 数据库密码有默认值 `local-demo-password` | 生产漏配环境变量时静默使用弱口令 | cloud profile 不提供默认值，缺失即启动失败 |
| M9 | `AuthUserService.java:77-84` | `update` 先更新再全表 `list()` 过滤取回，且两步不在同一事务 | 性能差；并发修改时返回值可能不是本次写入结果 | 按 id 单条查询，置于同一事务 |

### 低

| # | 位置 | 问题 | 修复建议 |
|---|---|---|---|
| L1 | `auth-service/.../SigningKeyConfiguration.java:17-36` | 单一签名密钥，无轮换机制（JWKS 只发布一个 kid） | 支持新旧双 kid 并存的轮换流程，写入运维手册 |
| L2 | `WorkOrderController.java:8,15`；`FeignConfiguration.java:14-15`；`AuthUserService` 等 | 大量全限定类名注解、单行多语句（如 `WorkOrderService.java:13`） | 统一 import 与格式化，引入 Spotless/Checkstyle |
| L3 | `WorkOrderService.java`、`DeviceController.java` 等 | 部分类缺少类级中文 Javadoc（AGENTS.md 要求类/接口/record 均有） | 补充 Javadoc，评审清单强制检查 |
| L4 | `DeviceFallbackFactory.java:22` | 日志只打 `cause.toString()`，丢失堆栈 | 以 `LOG.warn("...", cause)` 记录，或至少在 debug 级别输出堆栈 |
| L5 | `cloud-gateway/.../RequestIdGatewayFilter.java:13-16` | 无访问日志（方法、路径、状态、耗时） | 增加网关访问日志，配合运维 P4 的 JSON 日志与 Trace |

## 3. 测试体系方案

### 3.1 测试分层与比例

| 层级 | 目标 | 工具 | 运行时机 | Maven 阶段 |
|---|---|---|---|---|
| 单元测试 | 领域对象、状态机、Service 分支逻辑 | JUnit 5 + Mockito + AssertJ | 每次提交 | `test`（surefire，`*Test`） |
| 切片测试 | Controller 参数校验/权限、Repository SQL | `@WebMvcTest`、`@JdbcTest`/`@DataJpaTest` | 每次提交 | `test` |
| 集成测试 | 真实 MySQL、Nacos、Flyway 迁移 | Testcontainers | 每次 PR | `verify`（failsafe，`*IT`） |
| 契约测试 | 服务间 Feign 接口兼容 | Spring Cloud Contract | 每次 PR | `verify` |
| 端到端 | 减速机演示业务闭环 | docker compose + REST Assured | 合并 main 后 / 发布前 | 独立 job |

比例目标约 70% 单元/切片、20% 集成与契约、10% 端到端。

### 3.2 单元测试规范

- JUnit 5 + Mockito（`@ExtendWith(MockitoExtension.class)`）+ AssertJ，禁止在单测中启动 Spring 容器。
- 命名：`方法_场景_期望`，`@DisplayName` 用中文；测试内用中文注释说明“保护的行为”（AGENTS.md 要求）。
- 优先补：`WorkOrderService`（设备离线/不可用/成功三分支，当前分支覆盖 61.1%）、`DeviceFallbackFactory` 各异常映射、`ApiExceptionHandler`、`AuthUserService` 锁定计数与自锁保护、后续工单状态机全部合法/非法迁移。
- 时间相关逻辑注入 `Clock`，不在测试中 sleep。

### 3.3 Testcontainers 集成测试

- 依赖：`org.testcontainers:mysql`、`junit-jupiter`，配合 Spring Boot 3.1+ 的 `@ServiceConnection`。
- **MySQL**：使用与生产一致的 `mysql:8.x` 镜像；容器在测试类间复用（静态容器 + 抽象基类 `AbstractMySqlIT`）。
- **Flyway**：直接复用 `src/main/resources/db/migration` 脚本建库，测试即验证迁移脚本本身；演示数据放在 `db/demo` 独立 location，测试 profile 仅配置 `spring.flyway.locations=classpath:db/migration`，**不加载 demo 数据**，测试数据由各用例自行准备并清理。
- **Nacos**：用 `GenericContainer("nacos/nacos-server:<与 compose 同版本>")`，`MODE=standalone`，验证配置导入与服务注册；仅放在少量 `*NacosIT` 中，避免拖慢整体。
- 必测场景：`FOR UPDATE` 下并发 5 次失败登录计数准确；唯一键冲突翻译为 409；工单幂等键并发重复提交只生成一条；乐观锁冲突。
- CI 运行于 `ubuntu-latest`（自带 Docker）；Windows 矩阵仅跑单测与切片测试。

### 3.4 契约测试选型

| 维度 | Spring Cloud Contract | Pact |
|---|---|---|
| 技术栈契合 | 原生 Spring/Maven，生成 Provider 测试与 WireMock Stub | 多语言，JVM 支持良好但需额外插件 |
| 基础设施 | Stub 以 Maven artifact 发布，无需额外服务 | 推荐部署 Pact Broker 管理版本与 can-i-deploy |
| 驱动方式 | Provider 定义契约（也可 consumer-driven） | 纯 Consumer-driven |
| 适合场景 | 单仓库、全 Java、团队统一 | 多团队/多语言、前端也参与 |

**选型：Spring Cloud Contract。** 理由：本项目为单仓库全 Java/Spring 微服务，契约放在 provider（如 `device-service/src/test/resources/contracts`），构建时生成 provider 验证测试与 stub jar，`workorder-service` 用 `@AutoConfigureStubRunner` 消费，无需部署 Pact Broker，CI 改动最小。若后续引入独立前端或外部系统集成，再评估 Pact。

首批契约：`GET /devices/{id}`（ONLINE / OFFLINE / 404 / 401 / 403），对应 `DeviceFallbackFactory` 的各个映射分支。

### 3.5 端到端场景（小型齿轮减速机演示工艺）

使用 `make demo`（运维 P1）启动完整环境，加载 demo 数据后通过网关以 REST Assured 执行：

1. **登录**：ADMIN/OPERATOR/VIEWER 分别获取令牌；VIEWER 下单应返回 403。
2. **下工单**：计划员为“小型齿轮减速机”成品下达工单（带幂等键），校验多层 BOM 展开（箱体、输入轴、齿轮副、轴承、端盖）与工艺路线（机加工→装配→测试包装）生成工序任务；重复提交同一幂等键只生成一条；设备离线时拒绝。
3. **报工**：操作员按工序报工（合格数/不良数），校验报工数量不超过上道工序完工数、并发报工无丢失更新。
4. **质检**：首检/过程检/终检（如噪声、温升测试）录入结果；不合格品触发处置，工单不能在质检未通过时完工。
5. **追溯**：按成品序列号反查批次、所用物料批次、工序、设备、操作人与质检记录（正向/反向追溯）。

具体物料编码、BOM 与工艺路线以 MES业务开发的领域设计文档为准，E2E 数据与 `db/demo` 保持同一份来源。

### 3.6 CI 测试 job 与覆盖率门禁

在现有 `build.yml` 基础上拆分（构建/发布镜像部分由运维负责）：

| Job | 内容 | 平台 |
|---|---|---|
| `unit` | `mvnw -B verify -DskipITs`（单测 + 切片 + JaCoCo check） | Linux + Windows |
| `integration` | `mvnw -B verify`（failsafe 跑 Testcontainers `*IT` 与契约测试） | Linux |
| `e2e` | compose 启动演示环境 → 运行 E2E 套件 → 收集日志 | Linux，main 分支与发布前 |
| `coverage` | `report-aggregate` 汇总，上传 HTML 报告为 artifact，PR 评论摘要 | Linux |

JaCoCo 门禁（根 pom `jacoco:check`，按模块 BUNDLE 统计）：

- 初始：行覆盖 ≥ **70%**、分支覆盖 ≥ **60%**（不低于当前最低模块实测值，device-service 行 66.7% 需先补测试达标）。
- 领域服务（工单、计划、质检等）落地后：行 ≥ **80%**、分支 ≥ **70%**。
- 排除：`*Application`、纯 record DTO、配置类。
- PR 新增代码覆盖不得低于 80%（可用 diff-cover 或 Codecov patch 检查）。

## 4. PR 评审清单

合并前由 MES测试与质量逐项检查，任何一项不满足不予批准；严重问题直接打回，小问题在评论中标注。

- [ ] **CI 全绿**：unit / integration（及适用的 e2e）全部通过，JaCoCo 门禁通过，无被 `@Disabled` 跳过的新测试。
- [ ] **新逻辑有测试**：新增领域规则/状态迁移有单测；新接口有成功、校验失败、越权（401/403）测试；跨服务接口有契约；涉及 SQL 的有 Testcontainers 集成测试。
- [ ] **事务与并发正确**：事务边界在 Service 层且明确；写接口幂等；并发更新有乐观锁或行锁；跨服务调用不在本地事务中长时间持有连接，补偿/预留方案写清楚。
- [ ] **数据库变更**：通过新增 Flyway 版本脚本完成，不修改已发布脚本；演示数据只进 `db/demo`。
- [ ] **安全**：新接口同步维护网关规则与 `@PreAuthorize`，不信任外部身份头，不记录令牌/密码。
- [ ] **中文注释（AGENTS.md）**：新增/修改的类、接口、record 有中文 Javadoc；关键逻辑（跨服务调用、限流降级、异常映射、一致性、校验、资源清理）有中文说明；测试有中文意图注释；SQL/YAML/Dockerfile 有中文注释；无失效注释。
- [ ] **分层与规范**：Controller 不直写 SQL；状态用枚举；接口返回统一 `ApiResponse`，错误码稳定；文档（README / architecture / validation）同步更新。
