# 使用独立测试设备验证真实 AT 提交和远程回滚，结束后通过全局释放接口清理预留。
param([string]$BaseUrl='http://localhost:8080')
$ErrorActionPreference='Stop'
& (Join-Path $PSScriptRoot 'init-auth.ps1')
$login=Invoke-RestMethod "$BaseUrl/api/auth/login" -Method Post -ContentType 'application/json' -Body (
    @{username='admin';password=$env:AUTH_BOOTSTRAP_PASSWORD}|ConvertTo-Json -Compress)
$headers=@{Authorization="Bearer $($login.data.accessToken)"}
# 远程业务失败应映射原状态，不能被包装成500或成功。
function Assert-ReservationRejected([string]$Id,[string]$Device,[int]$Expected){
    try{
        Invoke-RestMethod "$BaseUrl/api/workorders/reservations" -Headers $headers -Method Post -ContentType 'application/json' -Body (
            @{reservationId=$Id;deviceId=$Device}|ConvertTo-Json -Compress)|Out-Null
        throw 'Rejected reservation unexpectedly succeeded'
    }catch{
        if(-not $_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ne $Expected){throw}
    }
}
$reservationId=[guid]::NewGuid().ToString()
$deviceA='S-'+[guid]::NewGuid().ToString('N').Substring(0,12)
$deviceB='S-'+[guid]::NewGuid().ToString('N').Substring(0,12)
# 测试设备 ID 仅由本脚本生成安全字符，SQL 不拼接任何外部输入。
$sql="INSERT INTO manufacturing_device.device VALUES ('$deviceA','Seata QA A','ONLINE'),('$deviceB','Seata QA B','ONLINE');"
docker compose exec -T mysql mysql -uroot -plocal-root-password -e $sql
if($LASTEXITCODE -ne 0){throw 'Test device initialization failed'}
try{
    Invoke-RestMethod "$BaseUrl/api/workorders/reservations" -Headers $headers -Method Post -ContentType 'application/json' -Body (
        @{reservationId=$reservationId;deviceId=$deviceA}|ConvertTo-Json -Compress)|Out-Null
    $devices=Invoke-RestMethod "$BaseUrl/api/devices/reservations" -Headers $headers
    if(@($devices.data|Where-Object {$_.deviceId -eq $deviceA}).Count -ne 1){throw 'Global commit failed'}
    Assert-ReservationRejected ([guid]::NewGuid().ToString()) $deviceA 409
    Assert-ReservationRejected ([guid]::NewGuid().ToString()) 'D002' 409
    Assert-ReservationRejected ([guid]::NewGuid().ToString()) ('missing-'+[guid]::NewGuid().ToString('N')) 404
    # 远程分支允许同预留编号暂时写入第二台设备；本地唯一单号冲突必须撤销该分支。
    try{
        Invoke-RestMethod "$BaseUrl/api/workorders/reservations" -Headers $headers -Method Post -ContentType 'application/json' -Body (
            @{reservationId=$reservationId;deviceId=$deviceB}|ConvertTo-Json -Compress)|Out-Null
        throw 'Duplicate reservation unexpectedly succeeded'
    }catch{
        if(-not $_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ne 409){throw}
    }
    $rolledBack=$false
    for($i=0;$i -lt 30;$i++){
        $devices=Invoke-RestMethod "$BaseUrl/api/devices/reservations" -Headers $headers
        if(@($devices.data|Where-Object {$_.deviceId -eq $deviceB}).Count -eq 0){$rolledBack=$true;break}
        Start-Sleep -Seconds 1
    }
    if(-not $rolledBack){throw 'Remote AT branch did not roll back'}
    $orders=Invoke-RestMethod "$BaseUrl/api/workorders/reservations" -Headers $headers
    $row=@($orders.data|Where-Object {$_.reservationId -eq $reservationId})
    if($row.Count -ne 1 -or $row[0].deviceId -ne $deviceA){throw 'Original committed reservation changed'}
    Write-Host 'PASS: real Seata AT commit and remote rollback after local SQL conflict.'
}finally{
    Invoke-RestMethod "$BaseUrl/api/workorders/reservations/$reservationId" -Headers $headers -Method Delete|Out-Null
    # 清理也校验两个库的结果，避免仅返回成功却遗留设备占用。
    $remaining=Invoke-RestMethod "$BaseUrl/api/devices/reservations" -Headers $headers
    $remainingOrders=Invoke-RestMethod "$BaseUrl/api/workorders/reservations" -Headers $headers
    if(@($remaining.data|Where-Object {$_.reservationId -eq $reservationId}).Count -ne 0 -or
       @($remainingOrders.data|Where-Object {$_.reservationId -eq $reservationId}).Count -ne 0){throw 'Global release failed'}
    $sql="DELETE FROM manufacturing_device.device WHERE id IN ('$deviceA','$deviceB');"
    docker compose exec -T mysql mysql -uroot -plocal-root-password -e $sql
}
