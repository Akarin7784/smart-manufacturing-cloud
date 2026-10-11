# 验证结果

验证日期：2026-10-12。环境：Windows、JDK 21、Maven Wrapper 3.9.9、Docker Desktop。

| 检查 | 结果 |
| --- | --- |
| Maven 全模块 verify | 成功，五个 Java 模块完成构建 |
| 自动测试 | 10 项通过：网关 2、设备 3、工单 5 |
| local profile 实际进程冒烟 | 通过，H2 文件数据库、网关、Feign |
| cloud profile 本机进程冒烟 | 通过，Nacos 服务发现、MySQL |
| 完整 Compose app profile | 构建与启动成功，容器之间服务发现与冒烟通过 |
| Nacos 配置导入 | 四个 YAML 配置发布成功 |
| Nacos 动态刷新 | 发布 demo.welcome 临时值，设备接口在不重启时更新；已恢复原配置 |
| Sentinel 网关限流 | 临时 QPS=0 规则返回 HTTP 429 / RATE_LIMITED；已恢复原规则 |
| Sentinel 设备列表限流 | 自动回归测试确认 /devices 返回 HTTP 429 / RATE_LIMITED |
| 真实设备进程故障 | 工单创建返回 HTTP 503 / DEVICE_SERVICE_UNAVAILABLE，工单数量未增加 |
| 业务错误链路 | 参数错误 400、设备不存在 404、设备离线 409 |
| Compose 配置与 PowerShell 语法 | 校验通过 |
| 容器运行时日志与用户目录 | 三个应用容器未出现 ERROR / 登录失败；Nacos 日志目录可写 |

本次验证结束后保留完整 Compose 环境运行。测试创建的示例工单保留在开发数据库中；H2 和 MySQL 的数据相互独立。

Sentinel 控制台规则仍为内存规则；本次未实现规则持久化。认证授权、RocketMQ、Seata、生产集群和性能压测不在本次完成范围内。相关扩展说明见 architecture.md。
