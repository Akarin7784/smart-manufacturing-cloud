# 通过真实登录和工单 API 验证事件从本地发件箱经过 Broker 到达独立消费数据库。
param([string]$BaseUrl = 'http://localhost:8080')
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'init-auth.ps1')
$login = Invoke-RestMethod "$BaseUrl/api/auth/login" -Method Post -ContentType 'application/json' -Body (
    @{username='admin';password=$env:AUTH_BOOTSTRAP_PASSWORD} | ConvertTo-Json -Compress)
$headers = @{Authorization="Bearer $($login.data.accessToken)"}
$order = Invoke-RestMethod "$BaseUrl/api/workorders" -Headers $headers -Method Post -ContentType 'application/json' -Body '{"deviceId":"D001","product":"mq-smoke","quantity":1}'
$received = $false
for ($attempt=0; $attempt -lt 60; $attempt++) {
    $events = Invoke-RestMethod "$BaseUrl/api/events/workorders" -Headers $headers
    $matching = @($events.data | Where-Object { $_.orderId -eq $order.data.id })
    if ($matching.Count -eq 1) { $received=$true; break }
    if ($matching.Count -gt 1) { throw 'Duplicate event projection detected.' }
    Start-Sleep -Seconds 2
}
if (-not $received) { throw 'Work-order event did not reach consumer within 120 seconds.' }
$outbox = Invoke-RestMethod "$BaseUrl/api/workorders/outbox" -Headers $headers
if ($outbox.data.SENT -lt 1) { throw 'No confirmed outbox event found.' }
Write-Host 'PASS: authenticated work order -> transactional outbox -> RocketMQ -> idempotent event projection.'
