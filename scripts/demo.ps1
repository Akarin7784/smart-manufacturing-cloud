# 一条命令启动完整演示环境（Windows 版，对应 scripts/demo.sh / make demo）：
# 准备 .env → 构建并启动全部容器并等待 healthy（Nacos 配置由 nacos-config-init 自动导入）→ 冒烟测试。
param([ValidateSet('up','down','reset','smoke')][string]$Action = 'up', [int]$WaitTimeout = 600)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    & (Join-Path $PSScriptRoot 'init-env.ps1')
    switch ($Action) {
        'down' { docker compose --profile app down; return }
        # reset 删除数据卷和本项目构建的本地镜像（--rmi local）回到干净状态，.env 保留。
        'reset' { docker compose --profile app down -v --rmi local; if ($LASTEXITCODE -ne 0) { throw 'compose down failed.' } }
        'smoke' { & (Join-Path $PSScriptRoot 'smoke-test.ps1'); return }
    }
    docker compose --profile app up -d --build --wait --wait-timeout $WaitTimeout
    if ($LASTEXITCODE -ne 0) { docker compose --profile app ps -a; throw 'Demo environment did not become healthy.' }
    & (Join-Path $PSScriptRoot 'smoke-test.ps1')
    Write-Host '演示环境已就绪：网关 http://localhost:8080 ，Nacos 控制台 http://localhost:8849 ；管理员 admin，密码见 .env'
} finally { Pop-Location }
