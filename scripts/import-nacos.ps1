# 等待 Nacos 就绪后初始化管理员密码并导入项目配置；使用 Nacos 3 运维接口和统一配置分组。
# Nacos 已开启鉴权：账号密码来自 .env（经 init-env.ps1 加载），导入请求携带登录得到的 accessToken。
# compose 的 nacos-config-init 容器会自动执行同样逻辑（import-nacos.sh），本脚本用于手动重新导入。
param([string]$NacosUrl = 'http://localhost:8848', [string]$Namespace = '')
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'init-env.ps1')
$ready = $false
for ($attempt = 0; $attempt -lt 60; $attempt++) {
    try {
        $state = Invoke-RestMethod "$NacosUrl/nacos/v3/admin/core/state/readiness" -TimeoutSec 3
        if ($state.code -eq 0 -and $state.data -eq 'ok') { $ready = $true; break }
    } catch { Start-Sleep -Seconds 2 }
}
if (-not $ready) { throw 'Nacos did not become ready.' }
# 首次启动设置管理员密码；已初始化时接口报错属于预期，忽略即可。
try { Invoke-RestMethod -Method Post "$NacosUrl/nacos/v3/auth/user/admin" -Body @{ password = $env:NACOS_PASSWORD } | Out-Null } catch { }
$login = Invoke-RestMethod -Method Post "$NacosUrl/nacos/v3/auth/user/login" -Body @{ username = $env:NACOS_USERNAME; password = $env:NACOS_PASSWORD }
$headers = @{ accessToken = $login.accessToken }
$directory = Join-Path (Split-Path -Parent $PSScriptRoot) 'infra/nacos'
foreach ($file in Get-ChildItem -LiteralPath $directory -Filter '*.yml') {
    $body = @{ dataId = $file.Name; groupName = 'MANUFACTURING'; type = 'yaml'; content = [IO.File]::ReadAllText($file.FullName) }
    $body.namespaceId = $(if ($Namespace) { $Namespace } else { 'public' })
    $result = Invoke-RestMethod -Method Post -Uri "$NacosUrl/nacos/v3/admin/cs/config" -Headers $headers -Body $body -ContentType 'application/x-www-form-urlencoded; charset=utf-8'
    if ($result.code -ne 0 -or $result.data -ne $true) { throw "Failed to import $($file.Name): $($result | ConvertTo-Json -Compress)" }
    Write-Host "Imported MANUFACTURING/$($file.Name)"
}
