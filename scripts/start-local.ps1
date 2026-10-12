# 启动本机 Java 服务；固定工作目录并保存进程编号和日志，便于后续停止与排查。
param([ValidateSet('local','cloud')][string]$Profile = 'local', [switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    $runtime = Join-Path $root '.runtime'
    New-Item -ItemType Directory -Force $runtime | Out-Null
    & (Join-Path $PSScriptRoot 'init-auth.ps1')
    # cloud profile 需要 .env 中的 Nacos 账号与数据库密码（Nacos 已开启鉴权），统一由 init-env.ps1 加载。
    if ($Profile -eq 'cloud') { & (Join-Path $PSScriptRoot 'init-env.ps1') }
    $modules = @('auth-service','device-service','workorder-service','masterdata-service','cloud-gateway')
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
        # 运行目录使用 JAR 副本，避免 Windows 占用 target 文件导致后续 Maven 打包失败。
        $runJar = Join-Path $runtime "$module-1.0.0-SNAPSHOT.jar"
        Copy-Item -LiteralPath $jar -Destination $runJar -Force
        $arguments = "-jar `"$runJar`" --spring.profiles.active=$Profile"
        $process = Start-Process -FilePath 'java' -ArgumentList $arguments -WorkingDirectory $root -WindowStyle Hidden -PassThru -RedirectStandardOutput "$runtime/$module.out.log" -RedirectStandardError "$runtime/$module.err.log"
        Set-Content -LiteralPath "$runtime/$module.pid" -Value $process.Id
    }
    Write-Host "Services starting ($Profile). Gateway: http://localhost:8080 ; logs: $runtime"
} finally { Pop-Location }
