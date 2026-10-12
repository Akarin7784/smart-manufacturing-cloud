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
# 工单下达：GR-60 成品工单下达后冻结 BOM A01 / 工艺 R01，生成 8 道工序并持有 ACTIVE 物料预留；同键重放返回同一预留，换键返回 409。
$gr = Invoke-RestMethod "$BaseUrl/api/workorders" -Headers ($headers + @{'Idempotency-Key'=[guid]::NewGuid().ToString()}) -Method Post -ContentType 'application/json' -Body '{"deviceId":"D001","product":"FG-GR60-20","quantity":10}'
$relKey = [guid]::NewGuid().ToString()
$rel = Invoke-RestMethod "$BaseUrl/api/workorders/$($gr.data.id)/release" -Headers ($headers + @{'Idempotency-Key'=$relKey}) -Method Post -TimeoutSec 15
if ($rel.data.status -ne 'RELEASED' -or $rel.data.bomVersion -ne 'A01' -or $rel.data.routingVersion -ne 'R01' -or $rel.data.operations.Count -ne 8 -or $rel.data.reservation.status -ne 'ACTIVE') { throw 'Work order release result unexpected.' }
$relReplay = Invoke-RestMethod "$BaseUrl/api/workorders/$($gr.data.id)/release" -Headers ($headers + @{'Idempotency-Key'=$relKey}) -Method Post -TimeoutSec 15
if ($relReplay.data.reservation.reservationId -ne $rel.data.reservation.reservationId) { throw 'Release replay returned a different reservation.' }
Assert-HttpStatus { Invoke-RestMethod "$BaseUrl/api/workorders/$($gr.data.id)/release" -Headers ($headers + @{'Idempotency-Key'=[guid]::NewGuid().ToString()}) -Method Post -TimeoutSec 15 } 409
# 服务身份（与 smoke-test.sh 对等）：用工单客户端密钥经网关换取服务令牌，解码载荷断言 scope/aud；错误密钥必须 401。
if (-not $env:WORKORDER_CLIENT_SECRET) {
    # 未导入环境时从 .env 读取明文密钥（与 smoke-test.sh 一致）。
    $envFile = Join-Path (Split-Path -Parent $PSScriptRoot) '.env'
    if (Test-Path -LiteralPath $envFile) {
        $line = Get-Content -LiteralPath $envFile -Encoding UTF8 | Where-Object { $_ -match '^WORKORDER_CLIENT_SECRET=.+$' } | Select-Object -First 1
        if ($line) { $env:WORKORDER_CLIENT_SECRET = $line.Substring('WORKORDER_CLIENT_SECRET='.Length) }
    }
}
if (-not $env:WORKORDER_CLIENT_SECRET) { throw 'WORKORDER_CLIENT_SECRET is missing.' }
$basic = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("workorder-service:$($env:WORKORDER_CLIENT_SECRET)"))
$cc = Invoke-RestMethod "$BaseUrl/api/auth/oauth2/token" -Method Post -Headers @{ Authorization = $basic } `
    -ContentType 'application/x-www-form-urlencoded' -Body 'grant_type=client_credentials&scope=inventory%3Areserve' -TimeoutSec 10
if ($cc.token_type -ne 'Bearer' -or $cc.scope -ne 'inventory:reserve' -or $cc.expires_in -ne 300) { throw 'Unexpected client_credentials response.' }
# JWT 载荷为 base64url：替换字符集并补齐填充后解码。
$part = $cc.access_token.Split('.')[1].Replace('-', '+').Replace('_', '/')
$part = $part + ('=' * ((4 - $part.Length % 4) % 4))
$claims = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($part)) | ConvertFrom-Json
if ($claims.scope -ne 'inventory:reserve' -or @($claims.aud) -notcontains 'inventory-service' -or $claims.token_type -ne 'service') {
    throw 'Unexpected service token claims.'
}
$wrong = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('workorder-service:wrong-secret'))
Assert-HttpStatus { Invoke-RestMethod "$BaseUrl/api/auth/oauth2/token" -Method Post -Headers @{ Authorization = $wrong } `
    -ContentType 'application/x-www-form-urlencoded' -Body 'grant_type=client_credentials' -TimeoutSec 10 } 401
Write-Host 'PASS: service identity (client_credentials scope/aud, wrong secret 401).'
# RocketMQ 收发回环（与 smoke-test.sh 对等）：在 broker 容器内用自带 mqadmin 发一条唯一标记消息，再以新消费组拉回比对。
# 只用镜像自带工具，不给业务镜像增加依赖；工具 JVM 堆压到 128M。设置环境变量 SKIP_MQ_SMOKE=1 可跳过。
if ($env:SKIP_MQ_SMOKE -ne '1') {
    $marker = "smoke-$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())-$PID"
    $group = "CG_SMOKE_$PID"
    # 参数经 -e 环境变量传入容器，避免 PowerShell 与容器内 sh 两层转义。
    $mqBase = @('compose', '--profile', 'app', 'exec', '-T', '-e', 'JAVA_OPT_EXT=-Xms64m -Xmx128m -Xmn32m', '-e', "MARKER=$marker", '-e', "GROUP=$group", 'broker', 'sh', '-c')
    $sent = & docker @mqBase '"$ROCKETMQ_HOME/bin/mqadmin" sendMessage -n namesrv:9876 -t mes-smoke-test -p "$MARKER" -k "$MARKER"' 2>$null
    if (-not ($sent -match 'SEND_OK')) { throw 'RocketMQ send failed.' }
    # 最多重试约 30 秒，拉到刚发送的唯一标记即视为成功。
    $got = $false
    for ($j = 0; $j -lt 15 -and -not $got; $j++) {
        $out = & docker @mqBase '"$ROCKETMQ_HOME/bin/mqadmin" consumeMessage -n namesrv:9876 -t mes-smoke-test -g "$GROUP" -c 1000' 2>$null
        if ($out -match [regex]::Escape($marker)) { $got = $true } else { Start-Sleep -Seconds 2 }
    }
    if (-not $got) { throw 'RocketMQ round-trip failed: sent message not received.' }
    Write-Host 'PASS: RocketMQ round-trip (topic mes-smoke-test).'
}
Write-Host 'PASS: login, JWT verification, routing, Feign bearer relay, persistence, business errors and work order release.'
