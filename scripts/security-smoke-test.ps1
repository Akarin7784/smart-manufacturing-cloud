# 验证角色权限与签名篡改；临时账号在测试结束后禁用，不打印任何密码或令牌。
param([string]$BaseUrl = 'http://localhost:8080')
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'init-auth.ps1')
function Assert-Denied([scriptblock]$Action, [int]$Expected) {
    try { & $Action | Out-Null; throw "Expected HTTP $Expected" }
    catch {
        if (-not $_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ne $Expected) { throw }
    }
}
$login = Invoke-RestMethod "$BaseUrl/api/auth/login" -Method Post -ContentType 'application/json' -Body (
    @{username='admin';password=$env:AUTH_BOOTSTRAP_PASSWORD} | ConvertTo-Json -Compress)
$admin = @{ Authorization = "Bearer $($login.data.accessToken)" }
$created = @()
try {
    foreach ($role in @('VIEWER','OPERATOR')) {
        $name = 'qa-' + $role.ToLower() + '-' + ([guid]::NewGuid().ToString('N').Substring(0,12))
        $password = [guid]::NewGuid().ToString('N')
        $user = Invoke-RestMethod "$BaseUrl/api/auth/users" -Headers $admin -Method Post -ContentType 'application/json' -Body (
            @{username=$name;password=$password;role=$role} | ConvertTo-Json -Compress)
        $created += @{id=$user.data.id;role=$role}
        $identity = Invoke-RestMethod "$BaseUrl/api/auth/login" -Method Post -ContentType 'application/json' -Body (
            @{username=$name;password=$password} | ConvertTo-Json -Compress)
        $headers = @{ Authorization = "Bearer $($identity.data.accessToken)" }
        Invoke-RestMethod "$BaseUrl/api/devices" -Headers $headers | Out-Null
        Assert-Denied { Invoke-RestMethod "$BaseUrl/api/auth/users" -Headers $headers } 403
        Assert-Denied { Invoke-RestMethod "$BaseUrl/api/devices/config" -Headers $headers } 403
        if ($role -eq 'VIEWER') {
            Assert-Denied { Invoke-RestMethod "$BaseUrl/api/workorders" -Headers ($headers + @{'Idempotency-Key'=[guid]::NewGuid().ToString()}) -Method Post -ContentType 'application/json' -Body '{"deviceId":"D001","product":"qa","quantity":1}' } 403
        } else {
            Invoke-RestMethod "$BaseUrl/api/workorders" -Headers ($headers + @{'Idempotency-Key'=[guid]::NewGuid().ToString()}) -Method Post -ContentType 'application/json' -Body '{"deviceId":"D001","product":"qa","quantity":1}' | Out-Null
        }
    }
    Assert-Denied { Invoke-RestMethod "$BaseUrl/api/devices" -Headers @{'X-Role'='ADMIN'} } 401
    # 只改签名字节，保持 JWT 结构正确，验证网关实际执行密码学验签。
    $parts = $login.data.accessToken.Split('.')
    $encoded = $parts[2].Replace('-','+').Replace('_','/')
    $encoded = $encoded.PadRight($encoded.Length + ((4 - $encoded.Length % 4) % 4), '=')
    $signatureBytes = [Convert]::FromBase64String($encoded)
    $signatureBytes[0] = $signatureBytes[0] -bxor 1
    $parts[2] = [Convert]::ToBase64String($signatureBytes).TrimEnd('=').Replace('+','-').Replace('/','_')
    Assert-Denied { Invoke-RestMethod "$BaseUrl/api/devices" -Headers @{Authorization="Bearer $($parts -join '.')"} } 401
    Write-Host 'PASS: role boundaries, verified bearer relay, forged headers and tampered signatures.'
} finally {
    # 保留用户和工单记录，但禁止测试账号后续登录。
    foreach ($user in $created) {
        Invoke-RestMethod "$BaseUrl/api/auth/users/$($user.id)" -Headers $admin -Method Patch -ContentType 'application/json' -Body (
            @{role=$user.role;enabled=$false} | ConvertTo-Json -Compress) | Out-Null
    }
}
