# 验证真实服务链路：等待就绪、创建和查询工单，并检查业务错误状态；会保留示例工单。
param([string]$BaseUrl = 'http://localhost:8080')
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'init-auth.ps1')
function Assert-HttpStatus([scriptblock]$Action, [int]$Expected) {
    try { & $Action | Out-Null; throw "Expected HTTP $Expected" }
    catch {
        if (-not $_.Exception.Response) { throw }
        $actual = [int]$_.Exception.Response.StatusCode
        if ($actual -ne $Expected) { throw "Expected HTTP $Expected, got $actual" }
    }
}
$ready = $false
for ($attempt = 0; $attempt -lt 60; $attempt++) {
    try {
        # 从认证服务获取令牌，确认网关和全部业务服务已就绪。
        $login = Invoke-RestMethod "$BaseUrl/api/auth/login" -Method Post -ContentType 'application/json' -Body (
            @{username='admin';password=$env:AUTH_BOOTSTRAP_PASSWORD} | ConvertTo-Json -Compress) -TimeoutSec 5
        $headers = @{ Authorization = "Bearer $($login.data.accessToken)" }
        $devices = Invoke-RestMethod "$BaseUrl/api/devices" -Headers $headers -TimeoutSec 5
        $orders = Invoke-RestMethod "$BaseUrl/api/workorders" -Headers $headers -TimeoutSec 5
        if ($devices.code -eq 'OK' -and $orders.code -eq 'OK') { $ready = $true; break }
    } catch { Start-Sleep -Seconds 2 }
}
if (-not $ready) { throw 'Gateway/device service did not become ready.' }
# 固定本次幂等键，随后用同一键重放并断言返回同一工单。
$key = [guid]::NewGuid().ToString()
$order = Invoke-RestMethod "$BaseUrl/api/workorders" -Headers ($headers + @{'Idempotency-Key'=$key}) -Method Post -ContentType 'application/json; charset=utf-8' -Body '{"deviceId":"D001","product":"bearing","quantity":10}'
if ($order.code -ne 'OK' -or $order.data.status -ne 'CREATED') { throw 'Create order failed.' }
$replay = Invoke-RestMethod "$BaseUrl/api/workorders" -Headers ($headers + @{'Idempotency-Key'=$key}) -Method Post -ContentType 'application/json; charset=utf-8' -Body '{"deviceId":"D001","product":"bearing","quantity":10}'
if ($replay.data.id -ne $order.data.id) { throw 'Idempotent replay returned a different order.' }
$list = Invoke-RestMethod "$BaseUrl/api/workorders" -Headers $headers
if ($order.data.id -notin $list.data.id) { throw 'Created order not found in storage.' }
Assert-HttpStatus { Invoke-RestMethod "$BaseUrl/api/devices/missing" -Headers $headers } 404
Assert-HttpStatus { Invoke-RestMethod "$BaseUrl/api/workorders" -Headers ($headers + @{'Idempotency-Key'=[guid]::NewGuid().ToString()}) -Method Post -ContentType 'application/json' -Body '{"deviceId":"missing","product":"bearing","quantity":10}' } 404
Assert-HttpStatus { Invoke-RestMethod "$BaseUrl/api/workorders" -Headers ($headers + @{'Idempotency-Key'=[guid]::NewGuid().ToString()}) -Method Post -ContentType 'application/json' -Body '{"deviceId":"D002","product":"bearing","quantity":10}' } 409
Assert-HttpStatus { Invoke-RestMethod "$BaseUrl/api/workorders" -Headers ($headers + @{'Idempotency-Key'=[guid]::NewGuid().ToString()}) -Method Post -ContentType 'application/json' -Body '{"deviceId":"D001","product":"bearing","quantity":0}' } 400
# 主数据（经网关）：演示 BOM 已发布且 10 台展开结果与演示数据一致（20CrMnTi 圆钢合计 10.5 kg）。
$bom = Invoke-RestMethod "$BaseUrl/api/boms/FG-GR60-20/effective?date=2026-10-12" -Headers $headers
if ($bom.data.version -ne 'A01' -or $bom.data.status -ne 'RELEASED') { throw 'Demo BOM FG-GR60-20/A01 not released.' }
$explosion = Invoke-RestMethod "$BaseUrl/api/boms/FG-GR60-20/explosion?date=2026-10-12&quantity=10" -Headers $headers
$steel = ($explosion.data.lines | Where-Object componentCode -eq 'RM-20CRMNTI-D30' | Measure-Object -Property requiredQty -Sum).Sum
if ([decimal]$steel -ne [decimal]10.5) { throw "Unexpected BOM explosion result: steel=$steel" }
Write-Host 'PASS: login, JWT verification, routing, Feign bearer relay, persistence and business errors.'
