# 初始化开发管理员密码；仅保存到被 Git 忽略的 .env，不输出密码内容。
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $root '.env'
$lines = @()
if (Test-Path -LiteralPath $envPath) { $lines = @(Get-Content -LiteralPath $envPath -Encoding UTF8) }
$passwordLine = $lines | Where-Object { $_ -match '^AUTH_BOOTSTRAP_PASSWORD=.+$' } | Select-Object -First 1
if (-not $env:AUTH_BOOTSTRAP_PASSWORD) {
    if ($passwordLine) {
        $env:AUTH_BOOTSTRAP_PASSWORD = $passwordLine.Substring('AUTH_BOOTSTRAP_PASSWORD='.Length)
    } else {
        # 随机十六字节转换为十六进制，便于 PowerShell 和 Compose 共用同一配置。
        $authPasswordBytes = New-Object byte[] 16
        $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
        try { $generator.GetBytes($authPasswordBytes) } finally { $generator.Dispose() }
        $env:AUTH_BOOTSTRAP_PASSWORD = -join ($authPasswordBytes | ForEach-Object { $_.ToString('x2') })
    }
}
if ($env:AUTH_BOOTSTRAP_PASSWORD.Length -lt 12) { throw 'Bootstrap password must contain at least 12 characters.' }
if (-not $passwordLine) {
    $lines = @($lines | Where-Object { $_ -notmatch '^AUTH_BOOTSTRAP_PASSWORD=' })
    $lines += "AUTH_BOOTSTRAP_PASSWORD=$($env:AUTH_BOOTSTRAP_PASSWORD)"
    [IO.File]::WriteAllText($envPath, ($lines -join "`n") + "`n", [Text.UTF8Encoding]::new($false))
}
Write-Host 'Auth bootstrap password is configured in ignored .env (existing users are not reset).'
