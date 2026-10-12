# 认证授权

本阶段实现独立账号认证服务、Spring Security JWT 验签及接口级角色授权。登录采用自有 JSON API，不是完整 OAuth2 授权码或 OIDC 单点登录协议。

## 身份与权限链路

1. 客户端向 POST /api/auth/login 提交账号密码。
2. 认证服务从独立数据库读取 BCrypt 散列，验证密码并根据数据库角色签发 RS256 JWT。
3. 网关通过 JWKS 公钥验证签名、签发者、有效期和受众，再按路径与方法检查权限。
4. 业务服务再次验签，并通过方法权限控制保护接口；直连服务不能绕过认证。
5. 工单服务通过 Feign 传递已验证安全上下文中的 Bearer 令牌，设备服务仍独立校验。

请求头 X-Role、X-User-Id 等不构成身份依据。私钥只保存在认证服务的运行目录或数据卷；公开 JWKS 永远只含公钥。

## 角色权限

| 操作 | ADMIN 管理员 | OPERATOR 操作员 | VIEWER 查看者 |
| --- | --- | --- | --- |
| 查询设备 | 允许 | 允许 | 允许 |
| 查询工单 | 允许 | 允许 | 允许 |
| 创建工单 | 允许 | 允许 | 拒绝 |
| 查看动态配置演示 | 允许 | 拒绝 | 拒绝 |
| 读取监控指标 | 允许 | 拒绝 | 拒绝 |
| 用户列表、创建、角色及状态修改 | 允许 | 拒绝 | 拒绝 |
| 查看当前身份 | 允许 | 允许 | 允许 |

角色在 auth-service 的 Role.java 中映射为细分权限。登录请求不能自行指定权限。新增业务接口需要同时维护网关授权规则、服务方法授权及对应越权测试。

## 首次初始化与已有环境升级

管理员用户名默认为 admin，没有固定默认密码。运行初始化脚本生成随机密码到被忽略的 .env：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\init-auth.ps1
```

也可以复制 .env.example 为 .env，自行设置 AUTH_BOOTSTRAP_PASSWORD（至少十二个字符，UTF-8 编码最多七十二字节）。已有用户时不会重置密码；修改 .env 也不会修改已有账号密码。

本机开发：start-local.ps1 会加载该密码并启动四个服务，认证服务端口为 8083。直接用 java 启动时，需要先在同一 PowerShell 会话执行初始化脚本，让变量传入 Java 子进程。

完整容器环境：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\demo.ps1   # 或 make demo
powershell -ExecutionPolicy Bypass -File .\scripts\security-smoke-test.ps1
```

db-init 是一次性初始化容器，幂等创建认证、设备、工单三个独立库及专用账号，并把账号密码同步为 .env 中的 MYSQL_APP_PASSWORD。它正常执行后显示 Exited (0)，认证服务随后启动。设备和工单数据不被删除。认证私钥保存在 auth-keys 数据卷，只有认证服务挂载该卷。

如果使用本机 Java 的 cloud profile，先在基础设施就绪后运行：

```powershell
docker compose up -d --wait db-init nacos-config-init
powershell -ExecutionPolicy Bypass -File .\scripts\start-local.ps1 -Profile cloud -SkipBuild
```

不要同时运行本机服务和对应的 app 容器。

## 登录与调用

```powershell
& .\scripts\init-auth.ps1
$login = Invoke-RestMethod http://localhost:8080/api/auth/login -Method Post -ContentType 'application/json' -Body (
    @{username='admin';password=$env:AUTH_BOOTSTRAP_PASSWORD} | ConvertTo-Json -Compress)
$headers = @{Authorization="Bearer $($login.data.accessToken)"}
Invoke-RestMethod http://localhost:8080/api/auth/me -Headers $headers
Invoke-RestMethod http://localhost:8080/api/devices -Headers $headers
```

响应 data 包含 accessToken、tokenType、expiresIn、username、role、permissions。登录响应设置 Cache-Control: no-store。令牌不要写入公开日志、URL 查询串或代码仓库。

| 方法和路径 | 用途 |
| --- | --- |
| POST /api/auth/login | 账号登录 |
| GET /api/auth/jwks | 公钥发现，允许匿名访问 |
| GET /api/auth/me | 当前令牌身份 |
| GET /api/auth/users | 管理员查询用户 |
| POST /api/auth/users | 管理员创建用户，body 为 username、password、role |
| PATCH /api/auth/users/{id} | 管理员修改 role 和 enabled |

创建用户示例（先设置自己的用户密码变量，不使用固定示例密码）：

```powershell
Invoke-RestMethod http://localhost:8080/api/auth/users -Headers $headers -Method Post -ContentType 'application/json' -Body (
    @{username='operator';password=$newUserPassword;role='OPERATOR'} | ConvertTo-Json -Compress)
```

用户响应不包含密码散列。管理员不能禁用自己的账号或移除自己的管理员角色，避免意外锁死管理入口。用户名重复返回 409。

## 错误及生命周期

- 未登录、篡改、过期、签发者或受众错误：401 / AUTHENTICATION_REQUIRED。
- 身份有效但权限不足：403 / ACCESS_DENIED。
- 账号不存在、密码错误、禁用或锁定：统一 401 / INVALID_CREDENTIALS。
- 同一账号连续五次错误密码后锁定十五分钟；成功登录或管理员状态更新清除失败计数。
- 默认访问令牌有效期十五分钟，时间校验允许 Spring Security 默认的六十秒时钟偏差。
- 角色和权限是签发时快照。禁用用户或调整角色会阻止后续登录或改变新令牌，但已有令牌仍可能使用到过期。
- 本阶段没有刷新令牌、服务端即时吊销、密码修改、找回密码或 MFA。客户端退出应清除访问令牌；这不等于服务端即时撤销。

## 安全配置

| 环境变量 | 用途 |
| --- | --- |
| AUTH_ISSUER | 统一签发者，默认 http://localhost:8080；所有服务必须一致 |
| AUTH_AUDIENCE | 受众，默认 manufacturing-api；所有服务必须一致 |
| AUTH_JWK_SET_URI | 网关及业务服务公钥地址；本机默认 http://localhost:8083/auth/jwks，容器默认内部认证服务地址 |
| AUTH_PRIVATE_KEY_PATH | 仅认证服务私钥文件路径，本机 .runtime/auth/signing-key.json，容器 /home/app/security/signing-key.json |
| AUTH_BOOTSTRAP_USERNAME | 首次管理员用户名，默认 admin |
| AUTH_BOOTSTRAP_PASSWORD | 首次管理员密码，只在认证库为空时使用 |
| AUTH_TOKEN_TTL_SECONDS | 令牌有效期，默认 900 秒，配置范围 60 至 3600 秒 |

现有启动与冒烟脚本默认使用 admin；如改用其他引导用户名，需相应调整调用凭据。认证服务启动时读取 TTL 与签发参数，修改后应重启认证服务。

上线时为登录和 API 配置 HTTPS，保护 .env 与密钥目录访问权限，设置登录流量限制和审计告警；在多实例部署中使用受控共享签名材料及明确的公钥轮换方案。若需要即时注销、实时撤销权限或租户数据隔离，应进一步增加状态化吊销/版本校验与数据权限设计。本阶段权限控制粒度为接口级。
