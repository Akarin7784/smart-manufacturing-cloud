# 仅停止本项目记录且命令行匹配的 Java 进程，避免进程编号被复用后误停其他程序。
$ErrorActionPreference = 'Stop'
$runtime = Join-Path (Split-Path -Parent $PSScriptRoot) '.runtime'
foreach ($module in @('cloud-gateway','event-service','workorder-service','device-service','auth-service')) {
    $pidFile = Join-Path $runtime "$module.pid"
    if (Test-Path -LiteralPath $pidFile) {
        $processId = [int](Get-Content -LiteralPath $pidFile)
        $process = Get-CimInstance Win32_Process -Filter "ProcessId = $processId"
        if ($process -and $process.Name -match '^java(w)?\.exe$' -and $process.CommandLine -like "*$module-1.0.0-SNAPSHOT.jar*") {
            Stop-Process -Id $processId
            Write-Host "Stopped $module ($processId)"
        } elseif ($process) {
            Write-Warning "PID $processId is no longer $module; leaving it running."
        }
        Remove-Item -LiteralPath $pidFile
    }
}
