# 生成并加载本地 .env（PowerShell 版，对应 init-env.sh）：不存在时从 .env.example 复制，为留空密钥填充随机值，
# 然后把全部键值导入当前进程环境变量，供 import-nacos.ps1、start-local.ps1、smoke-test.ps1 使用。
# 已有非空值保持不变，避免与已初始化的数据卷密码失配。
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $root '.env'
if (-not (Test-Path -LiteralPath $envPath)) {
    Copy-Item -LiteralPath (Join-Path $root '.env.example') -Destination $envPath
    Write-Host '已从 .env.example 创建 .env'
}
$lines = [Collections.Generic.List[string]]@(Get-Content -LiteralPath $envPath -Encoding UTF8)
# 使用加密随机数生成 hex 字符串。
function New-RandomHex([int]$Bytes) {
    $buffer = New-Object byte[] $Bytes
    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($buffer) } finally { $generator.Dispose() }
    -join ($buffer | ForEach-Object { $_.ToString('x2') })
}
# 仅当键不存在或为空时写入，保证可重复执行。
function Set-IfEmpty([string]$Key, [string]$Value) {
    if ($lines | Where-Object { $_ -match "^$Key=.+$" }) { return }
    $script:lines = [Collections.Generic.List[string]]@($lines | Where-Object { $_ -notmatch "^$Key=" })
    $script:lines.Add("$Key=$Value")
    Write-Host "已生成 $Key"
}
Set-IfEmpty 'AUTH_BOOTSTRAP_PASSWORD' (New-RandomHex 16)
Set-IfEmpty 'MYSQL_ROOT_PASSWORD' (New-RandomHex 16)
Set-IfEmpty 'MYSQL_APP_PASSWORD' (New-RandomHex 16)
# Nacos 令牌密钥要求 Base64 且解码后至少 32 字节。
Set-IfEmpty 'NACOS_AUTH_TOKEN' ([Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes((New-RandomHex 24))))
Set-IfEmpty 'NACOS_AUTH_IDENTITY_KEY' 'serverIdentity'
Set-IfEmpty 'NACOS_AUTH_IDENTITY_VALUE' (New-RandomHex 16)
Set-IfEmpty 'NACOS_USERNAME' 'nacos'
Set-IfEmpty 'NACOS_PASSWORD' (New-RandomHex 16)
# 可选监控（compose monitoring profile）：Grafana 管理员账号与密码都随机生成，不使用镜像默认的 admin/admin。
Set-IfEmpty 'GRAFANA_ADMIN_USER' ('grafana-' + (New-RandomHex 4))
Set-IfEmpty 'GRAFANA_ADMIN_PASSWORD' (New-RandomHex 16)
Set-IfEmpty 'AUTH_ISSUER' 'http://localhost:8080'
Set-IfEmpty 'AUTH_AUDIENCE' 'manufacturing-api'
# 工单服务的 client_credentials 密钥：明文只给 workorder-service，认证服务只拿 BCrypt 散列。
# PowerShell 无内置 BCrypt：优先本机 htpasswd，否则用一次性 httpd 容器（--rm）生成。
function New-BcryptHash([string]$Secret) {
    $htpasswd = Get-Command htpasswd -ErrorAction SilentlyContinue
    # 使用非空占位用户名 svc，避免空字符串参数在 Windows PowerShell 5.1 与 PowerShell 7 下传参行为不一致；
    # 输出为 "svc:散列"，取第一个冒号之后的部分。cost 固定为 10，须与认证服务 ServiceClientRegistry.EXPECTED_COST 一致。
    if ($htpasswd) { $out = $Secret | & $htpasswd.Source -niBC 10 svc }
    else {
        # PowerShell 5.1 会把 docker 拉取进度（stderr）渲染成红色 NativeCommandError；
        # 先静默拉取镜像，再生成散列。散列走 stdout，不能重定向掉；失败由下方 $2 前缀校验报错。
        & docker pull -q httpd:2.4-alpine 2>$null | Out-Null
        $out = $Secret | & docker run --rm -i httpd:2.4-alpine htpasswd -niBC 10 svc 2>$null
    }
    $line = ("$out").Trim()
    $hash = $line.Substring($line.IndexOf(':') + 1)
    if (-not $hash.StartsWith('$2')) { throw 'BCrypt 散列生成失败：请安装 htpasswd 或启动 Docker' }
    $hash
}
$secretCreated = -not ($lines | Where-Object { $_ -match '^WORKORDER_CLIENT_SECRET=.+$' })
Set-IfEmpty 'WORKORDER_CLIENT_SECRET' (New-RandomHex 32)
# 明文新生成或散列缺失时重算散列，保证二者配对；散列含 $，用单引号写入避免 compose 插值。
if ($secretCreated -or -not ($lines | Where-Object { $_ -match '^WORKORDER_CLIENT_SECRET_HASH=.+$' })) {
    $plain = (($lines | Where-Object { $_ -match '^WORKORDER_CLIENT_SECRET=' }) -replace '^WORKORDER_CLIENT_SECRET=', '') | Select-Object -First 1
    $script:lines = [Collections.Generic.List[string]]@($lines | Where-Object { $_ -notmatch '^WORKORDER_CLIENT_SECRET_HASH=' })
    $script:lines.Add("WORKORDER_CLIENT_SECRET_HASH='$(New-BcryptHash $plain)'")
    Write-Host '已生成 WORKORDER_CLIENT_SECRET_HASH'
}
[IO.File]::WriteAllText($envPath, ($lines -join "`n") + "`n", [Text.UTF8Encoding]::new($false))
# 导入进程环境；已显式设置的环境变量优先，不被 .env 覆盖。
foreach ($line in $lines) {
    if ($line -match '^([A-Z_][A-Z0-9_]*)=(.*)$' -and -not [Environment]::GetEnvironmentVariable($Matches[1])) {
        # 去掉单引号包裹（如含 $ 的 BCrypt 散列），与 docker compose 的 .env 解析一致。
        [Environment]::SetEnvironmentVariable($Matches[1], ($Matches[2] -replace "^'(.*)'$", '$1'))
    }
}
# 本机 cloud profile 服务读取 MYSQL_PASSWORD，与容器内业务账号密码保持一致。
if (-not $env:MYSQL_PASSWORD) { $env:MYSQL_PASSWORD = $env:MYSQL_APP_PASSWORD }
