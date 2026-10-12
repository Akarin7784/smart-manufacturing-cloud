# 项目记忆与开发约定

## 用户长期要求

- 本项目后续新增或修改的代码都必须添加、维护中文注释。此要求由用户明确提出，适用于本仓库的所有后续开发。
- Java 类、接口和 record 应有中文 Javadoc，说明职责、业务用途或数据契约；公开接口方法、关键业务方法和具有特殊约束的方法应说明用途与边界。
- 关键逻辑必须使用中文注释解释设计原因，特别是跨服务调用、限流降级、异常映射、数据一致性、参数校验与资源清理。
- 新增或修改测试时，使用中文注释说明测试意图及需要保护的行为。
- 自行维护的 SQL、PowerShell、Dockerfile、YAML、XML 等也需要中文注释，说明配置用途、依赖关系、环境差异及重要约束。
- 注释应准确、简洁，重点解释业务含义和设计原因；无需逐行翻译代码语法。修改逻辑时同步更新注释，禁止保留失效说明。
- 文件使用 UTF-8 编码，确保中文可读。上游生成的 Maven Wrapper、第三方代码、依赖缓存和构建产物不要求手工补注释。

## 已有架构约定

- GitHub 仓库为 Akarin7784/smart-manufacturing-cloud，公开可见；默认分支 main。
- CI 使用 GitHub Actions，在 Linux / Windows 上执行 Java 21 全模块 verify，并保存测试报告及可执行服务 JAR。

- 技术栈为 Java 21、Spring Boot 3.5.0、Spring Cloud 2025.0.0、Spring Cloud Alibaba 2025.0.0.0。
- cloud-api 管理公共契约；cloud-common 管理 Servlet 公共组件；网关采用 WebFlux，不引用 Servlet 公共模块。
- cloud-security 共享 JWT 校验与权限映射；auth-service 使用独立认证库、BCrypt 密码散列和持久化 RSA 私钥。
- 网关及业务服务必须分别校验 JWT；接口通过明确权限授权，禁止信任外部用户/角色请求头。
- Feign 只传播已验证安全上下文中的 Bearer 令牌，不能伪造内部用户身份。
- ADMIN、OPERATOR、VIEWER 权限映射位于认证服务；新增接口须同步维护网关和方法权限规则及越权测试。
- 初始管理员密码只从环境或被忽略的 .env 注入；RSA 私钥不得提交到 Git。已有短期令牌不即时吊销，角色变更在新令牌中生效。
- 设备和工单服务使用独立数据库；服务间通过 OpenFeign 和 Nacos 服务发现调用。
- local 环境用于无中间件开发；cloud 环境启用 Nacos、Sentinel 和 MySQL；test 环境隔离外部依赖。
- 设备状态无法确认或设备离线时，不允许创建工单；降级不得伪造设备状态或业务成功。
- 设备状态查询与工单写入不具备跨服务原子性；后续资源占用流程需明确预留、幂等和补偿方案。

## 开发完成检查

- 先阅读本文件及相关模块代码，再实施修改。
- 检查新增和修改代码是否具有恰当的中文注释，注释是否与实际行为一致。
- 按修改影响范围执行必要检查；纯注释修改无需反复运行全部测试或重建运行环境。
- 启动、架构与验证文档分别维护在 README.md、docs/architecture.md 和 docs/validation.md。

## RocketMQ 约定

- cloud-messaging 使用 Alibaba BOM 管理的官方 RocketMQ 5.3.1 客户端；event-service 使用独立消费数据库。
- 工单与 Outbox 必须同事务提交，不能在控制器中直接执行写库后发消息。
- 发送保证至少一次；消费者以 eventId 幂等，同 ID 内容冲突必须拒绝，不得吞掉处理异常。
- 消息不能包含访问令牌、密码或私钥；事件管理仅管理员可访问，投影要求工单查询权限。
- 默认关闭 MQ 连接；使用 compose.rocketmq.yaml 与 app/mq profile 按需启用。启动或停止只操作 manufacturing-cloud，不影响其他项目。
