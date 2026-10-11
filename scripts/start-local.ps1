# 启动本机 Java 服务；固定工作目录并保存进程编号和日志，便于后续停止与排查。
param([ValidateSet('local','cloud')][string]$Profile = 'local', [switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    $runtime = Join-Path $root '.runtime'
    New-Item -ItemType Directory -Force $runtime | Out-Null
    $modules = @('device-service','workorder-service','cloud-gateway')
    foreach ($module in $modules) {
        $pidFile = Join-Path $runtime "$module.pid"
        if (Test-Path -LiteralPath $pidFile) {
            $existing = Get-Process -Id ([int](Get-Content -LiteralPath $pidFile)) -ErrorAction SilentlyContinue
            if ($existing) { throw "$module is already running; use scripts/stop-local.ps1 first." }
        }
    }
    if (-not $SkipBuild) {
        & "$root/mvnw.cmd" -B -ntp package -DskipTests
        if ($LASTEXITCODE -ne 0) { throw 'Maven build failed.' }
    }
    foreach ($module in $modules) {
        $jar = Join-Path $root "$module/target/$module-1.0.0-SNAPSHOT.jar"
        if (-not (Test-Path -LiteralPath $jar)) { throw "Missing JAR: $jar" }
        $arguments = "-jar `"$jar`" --spring.profiles.active=$Profile"
        $process = Start-Process -FilePath 'java' -ArgumentList $arguments -WorkingDirectory $root -WindowStyle Hidden -PassThru -RedirectStandardOutput "$runtime/$module.out.log" -RedirectStandardError "$runtime/$module.err.log"
        Set-Content -LiteralPath "$runtime/$module.pid" -Value $process.Id
    }
    Write-Host "Services starting ($Profile). Gateway: http://localhost:8080 ; logs: $runtime"
} finally { Pop-Location }
