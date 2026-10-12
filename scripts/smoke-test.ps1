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
# ---- inv_call：在 compose 内网直连库存服务（库存业务端口不发布到宿主机），与 smoke-test.sh 的 inv_call 同签名同语义 ----
# 用法：inv_call METHOD PATH [BODY]
#   METHOD  HTTP 方法，如 GET / POST
#   PATH    库存服务内路径，如 /inventory/reservations（不经网关，因此没有 /api 前缀）
#   BODY    可选，JSON 字符串；有值时以 Content-Type: application/json; charset=utf-8 经 stdin（UTF-8）发送
# 可选环境变量：
#   INV_TOKEN     Bearer 令牌（服务令牌，aud=inventory-service），非空时加 Authorization 头
#   INV_IDEM_KEY  Idempotency-Key 头，非空时添加；调用方负责每次生成新键
#   INV_BASE      目标地址，默认 http://inventory-service:8085（compose 中为 inventory 服务配置的网络别名）
#   INV_NETWORK   docker 网络名；默认取 compose 中 inventory 容器实际所在的网络，不依赖项目名拼写
# 返回：一个字符串，先是响应体，最后一行是 HTTP 状态码（与 sh 版输出完全一致）；
#   取状态码：[int](($r -split "`n")[-1])；取响应体：(($r -split "`n") | Select-Object -SkipLast 1) -join "`n"。
#   网络或容器错误时抛出异常。
# 实现：一次性 curl 容器（docker run --rm，不残留容器），镜像按标签+摘要固定，来源 scripts/tool-images.env。
$SmokeCurlImage = if ($env:SMOKE_CURL_IMAGE) { $env:SMOKE_CURL_IMAGE } else {
    ((Get-Content -LiteralPath (Join-Path $PSScriptRoot 'tool-images.env') -Encoding UTF8 | Where-Object { $_ -match '^SMOKE_CURL_IMAGE=' } | Select-Object -First 1) -replace '^SMOKE_CURL_IMAGE=', '')
}
function inv_network {
    if ($env:INV_NETWORK) { return $env:INV_NETWORK }
    $cid = & docker compose -f (Join-Path (Split-Path -Parent $PSScriptRoot) 'compose.yaml') ps -q inventory
    if ($LASTEXITCODE -ne 0 -or -not $cid) { throw 'inventory container is not running.' }
    $nets = & docker inspect -f '{{range $k, $v := .NetworkSettings.Networks}}{{println $k}}{{end}}' $cid
    return (@($nets) | Where-Object { $_ } | Select-Object -First 1)
}
function inv_call([string]$Method, [string]$Path, [string]$Body = '') {
    $net = inv_network
    $base = if ($env:INV_BASE) { $env:INV_BASE } else { 'http://inventory-service:8085' }
    $curlArgs = @('-sS', '-m', '10', '-X', $Method, '-w', '\n%{http_code}')
    if ($env:INV_TOKEN) { $curlArgs += @('-H', "Authorization: Bearer $($env:INV_TOKEN)") }
    if ($env:INV_IDEM_KEY) { $curlArgs += @('-H', "Idempotency-Key: $($env:INV_IDEM_KEY)") }
    # 管道与输出统一按无 BOM 的 UTF-8 处理，避免 Windows PowerShell 5.1 按本地代码页转码。
    $utf8 = New-Object System.Text.UTF8Encoding $false
    $prevOut = $OutputEncoding; $prevConsole = [Console]::OutputEncoding
    $OutputEncoding = $utf8; [Console]::OutputEncoding = $utf8
    try {
        if ($Body) {
            $out = $Body | & docker run --rm -i --network $net $SmokeCurlImage @curlArgs -H 'Content-Type: application/json; charset=utf-8' --data-binary '@-' "$base$Path"
        } else {
            $out = & docker run --rm --network $net $SmokeCurlImage @curlArgs "$base$Path"
        }
        if ($LASTEXITCODE -ne 0) { throw "inv_call $Method $Path failed (exit $LASTEXITCODE)." }
    } finally { $OutputEncoding = $prevOut; [Console]::OutputEncoding = $prevConsole }
    return (@($out) -join "`n")
}
function Get-InvStatus([string]$Response) { return [int](($Response -split "`n")[-1]) }
function Get-InvJson([string]$Response) { return ((($Response -split "`n") | Select-Object -SkipLast 1) -join "`n") | ConvertFrom-Json }

# 库存预留（服务令牌经 inv_call 在 compose 内网直连库存服务，模拟工单服务经 Nacos 直连；网关不放行预留接口）：
# 预留 → 断言可用量减少 → 释放（reason=CALLER_REQUEST）→ GET 确认 RELEASED → 断言可用量回到预留前；释放放在 finally 中，后续断言失败也会执行，冒烟可重复运行。
# 幂等键每次运行新生成（含运行 id），预留与释放各用各的，绝不复用。
# 若 finally 未能执行（进程被强杀等），预留在 15 分钟后由过期任务（inventory.reservation.ttl=PT15M）自动回收。
# 物料取 GR-60 BOM 中的 RM-20CRMNTI-D30（20CrMnTi 圆钢，演示期初 16 kg），预留 1 kg，单位用主数据编码 "kg"。
$env:INV_TOKEN = $cc.access_token
$runId = [guid]::NewGuid().ToString('N')
$availUrl = "$BaseUrl/api/inventory/stock/availability?materialCode=RM-20CRMNTI-D30"
$before = [decimal](Invoke-RestMethod $availUrl -Headers $headers -TimeoutSec 5).available
$reserveBody = @{ workOrderNo = "SMOKE-$runId"; lines = @(@{ materialCode = 'RM-20CRMNTI-D30'; qty = '1'; uom = 'kg'; sourceLineId = 'smoke-1' }) } | ConvertTo-Json -Depth 4 -Compress
$env:INV_IDEM_KEY = "smoke-reserve-$runId"
$res = inv_call POST /inventory/reservations $reserveBody
if ((Get-InvStatus $res) -ne 201) { throw "Reserve should return 201: $res" }
$reservation = Get-InvJson $res
if ($reservation.status -ne 'ACTIVE' -or -not $reservation.reservationId) { throw 'Reserve failed.' }
$releasePath = "/inventory/reservations/$($reservation.reservationId)/release"
$released = $false
try {
    $mid = [decimal](Invoke-RestMethod $availUrl -Headers $headers -TimeoutSec 5).available
    if ($before - $mid -ne 1) { throw "Available qty should drop by 1 ($before -> $mid)." }
    $env:INV_IDEM_KEY = "smoke-release-$runId"
    $rel = inv_call POST $releasePath '{"reason":"CALLER_REQUEST"}'
    if ((Get-InvStatus $rel) -ne 200 -or (Get-InvJson $rel).status -ne 'RELEASED') { throw "Release failed: $rel" }
    $released = $true
    # 通过 GET 再确认预留单已是终态 RELEASED（服务令牌，GET 不带幂等键）。
    $env:INV_IDEM_KEY = ''
    $got = inv_call GET "/inventory/reservations/$($reservation.reservationId)"
    if ((Get-InvStatus $got) -ne 200 -or (Get-InvJson $got).status -ne 'RELEASED') { throw "Reservation status should be RELEASED: $got" }
    $after = [decimal](Invoke-RestMethod $availUrl -Headers $headers -TimeoutSec 5).available
    if ($after -ne $before) { throw "Available qty should be restored ($before -> $after)." }
} finally {
    # 兜底释放：释放幂等（已释放再调用返回 200 与当前状态），同一幂等键重放也安全。
    if (-not $released) {
        try { $env:INV_IDEM_KEY = "smoke-release-$runId"; inv_call POST $releasePath '{"reason":"CALLER_REQUEST"}' | Out-Null }
        catch { Write-Warning "Cleanup release failed: $($_.Exception.Message)" }
    }
    $env:INV_IDEM_KEY = ''; $env:INV_TOKEN = ''
}
Write-Host 'PASS: inventory reserve/release (service token, available qty restored).'

# 质检权限（网关 + 真实质检服务，与 smoke-test.sh 对等）：用 init-env 生成的演示账号真实登录，验证角色映射。
# 服务已部署，不再接受 503：查询必须 200；无写权限的处置必须 403；有写权限的处置打到不存在的 NCR-SMOKE，只接受业务拒绝（400/404/422）。
function Get-DemoPassword([string]$Key) {
    $value = [Environment]::GetEnvironmentVariable($Key)
    if (-not $value) {
        $envFile = Join-Path (Split-Path -Parent $PSScriptRoot) '.env'
        if (Test-Path -LiteralPath $envFile) {
            $line = Get-Content -LiteralPath $envFile -Encoding UTF8 | Where-Object { $_ -match "^$Key=.+$" } | Select-Object -First 1
            if ($line) { $value = $line.Substring($Key.Length + 1) }
        }
    }
    if (-not $value) { throw "$Key is missing; run scripts/init-env.ps1." }
    return $value
}
function Get-DemoToken([string]$User, [string]$Key) {
    $r = Invoke-RestMethod "$BaseUrl/api/auth/login" -Method Post -ContentType 'application/json' -Body (
        @{ username = $User; password = (Get-DemoPassword $Key) } | ConvertTo-Json -Compress) -TimeoutSec 5
    return $r.data.accessToken
}
# 返回 HTTP 状态码（含 4xx/5xx），不抛异常。
function Get-QualityStatus([string]$Token, [string]$Method, [string]$Path) {
    try {
        $p = @{ Uri = "$BaseUrl$Path"; Method = $Method; Headers = @{ Authorization = "Bearer $Token" }; TimeoutSec = 10 }
        if ($Method -eq 'Post') { $p.ContentType = 'application/json'; $p.Body = '{}' }
        Invoke-WebRequest @p -UseBasicParsing | ForEach-Object { [int]$_.StatusCode }
    } catch {
        if (-not $_.Exception.Response) { throw }
        [int]$_.Exception.Response.StatusCode
    }
}
$qcToken = Get-DemoToken 'qc01' 'AUTH_DEMO_QC01_PASSWORD'
$opToken = Get-DemoToken 'op01' 'AUTH_DEMO_OP01_PASSWORD'
$adminToken = $login.data.accessToken
$disposePath = '/api/quality/ncrs/NCR-SMOKE/dispositions'
if ((Get-QualityStatus $opToken 'Post' $disposePath) -ne 403) { throw 'op01 dispose should be 403.' }
# 网关拒绝必须是 RFC 9457 problem+json（type=gateway-access-denied），且不再带旧的 code 字段。
try {
    Invoke-WebRequest -Uri "$BaseUrl$disposePath" -Method Post -Headers @{ Authorization = "Bearer $opToken" } -ContentType 'application/json' -Body '{}' -UseBasicParsing -TimeoutSec 10 | Out-Null
    throw 'op01 dispose should be rejected.'
} catch {
    $resp = $_.Exception.Response
    if (-not $resp) { throw }
    $ct = [string]$resp.Content.Headers.ContentType
    if (-not $ct) { $ct = [string]$resp.ContentType }
    if ($ct -notlike 'application/problem+json*') { throw "Gateway 403 Content-Type should be application/problem+json, got $ct." }
    $raw = if ($_.ErrorDetails -and $_.ErrorDetails.Message) { $_.ErrorDetails.Message } else {
        (New-Object System.IO.StreamReader($resp.GetResponseStream())).ReadToEnd() }
    $p = $raw | ConvertFrom-Json
    if ($p.type -ne 'gateway-access-denied' -or $p.status -ne 403 -or -not $p.title -or -not $p.detail -or $p.instance -ne $disposePath -or ($p.PSObject.Properties.Name -contains 'code')) {
        throw "Gateway 403 body is not the expected problem+json: $raw"
    }
}
foreach ($case in @(@($opToken, 'op01'), @($qcToken, 'qc01'))) {
    $code = Get-QualityStatus $case[0] 'Get' '/api/quality/ncrs'
    if ($code -ne 200) { throw "$($case[1]) GET /api/quality/ncrs should be 200, got $code." }
}
foreach ($case in @(@($qcToken, 'qc01'), @($adminToken, 'admin'))) {
    $code = Get-QualityStatus $case[0] 'Post' $disposePath
    if (@(400, 404, 422) -notcontains $code) { throw "$($case[1]) dispose on missing NCR should be rejected by quality-service (400/404/422), got $code." }
}
Write-Host 'PASS: quality permissions (op01 dispose 403, op01/qc01 read 200, qc01/admin dispose reach service).'

# 质检告警计数器：经 inv_call 的 curl 容器在 compose 内网读取 quality:18086（不发布到宿主机），断言三条告警序列（冲突、孤儿冲销、坏消息）已以 0 预注册。
$env:INV_TOKEN = ''; $env:INV_IDEM_KEY = ''; $env:INV_BASE = 'http://quality-service:18086'
try { $qm = inv_call GET /actuator/prometheus } finally { $env:INV_BASE = '' }
if ((Get-InvStatus $qm) -ne 200) { throw 'quality-service /actuator/prometheus on 18086 should be 200.' }
if ($qm -notmatch '(?m)^mes_quality_event_consumed_total\{[^}]*result="disposed_reversal_conflict"[^}]*\} 0(\.0)?\r?$') { throw 'disposed_reversal_conflict counter not pre-registered at 0.' }
if ($qm -notmatch '(?m)^mes_quality_event_consumed_total\{[^}]*result="invalid"[^}]*\} 0(\.0)?\r?$') { throw 'invalid counter not pre-registered at 0.' }
if ($qm -notmatch '(?m)^mes_quality_pending_reversal_expired_total(\{[^}]*\})? 0(\.0)?\r?$') { throw 'pending_reversal_expired counter not pre-registered at 0.' }
Write-Host 'PASS: quality alert counters pre-registered at 0 (internal 18086).'
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
