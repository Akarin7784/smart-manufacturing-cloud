# 等待 Nacos 就绪后导入项目配置；使用 Nacos 3 运维接口和统一配置分组。
param([string]$NacosUrl = 'http://localhost:8848', [string]$Namespace = '')
$ErrorActionPreference = 'Stop'
$ready = $false
for ($attempt = 0; $attempt -lt 60; $attempt++) {
    try {
        $state = Invoke-RestMethod "$NacosUrl/nacos/v3/admin/core/state/readiness" -TimeoutSec 3
        if ($state.code -eq 0 -and $state.data -eq 'ok') { $ready = $true; break }
    } catch { Start-Sleep -Seconds 2 }
}
if (-not $ready) { throw 'Nacos did not become ready.' }
$directory = Join-Path (Split-Path -Parent $PSScriptRoot) 'infra/nacos'
foreach ($file in Get-ChildItem -LiteralPath $directory -Filter '*.yml') {
    $body = @{ dataId = $file.Name; groupName = 'MANUFACTURING'; type = 'yaml'; content = [IO.File]::ReadAllText($file.FullName) }
    $body.namespaceId = $(if ($Namespace) { $Namespace } else { 'public' })
    $result = Invoke-RestMethod -Method Post -Uri "$NacosUrl/nacos/v3/admin/cs/config" -Body $body -ContentType 'application/x-www-form-urlencoded; charset=utf-8'
    if ($result.code -ne 0 -or $result.data -ne $true) { throw "Failed to import $($file.Name): $($result | ConvertTo-Json -Compress)" }
    Write-Host "Imported MANUFACTURING/$($file.Name)"
}
