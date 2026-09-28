# 停止本项目相关的进程：后端(8080)、前端(5173)、项目自带 MySQL(3307)。
# 只按端口停止，不会影响系统里其他端口的 MySQL 实例。
param(
    [int[]]$Ports = @(8080, 5173, 3307)
)

$ErrorActionPreference = 'Continue'

foreach ($port in $Ports) {
    $connections = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    if (-not $connections) {
        Write-Host "[停止] 端口 $port 没有监听进程。" -ForegroundColor DarkGray
        continue
    }
    $pids = $connections | Select-Object -ExpandProperty OwningProcess -Unique
    foreach ($processId in $pids) {
        $proc = Get-Process -Id $processId -ErrorAction SilentlyContinue
        if (-not $proc) { continue }
        Write-Host "[停止] 端口 $port -> $($proc.ProcessName) (PID $processId)" -ForegroundColor Yellow
        Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue
    }
}

Start-Sleep -Seconds 1
Write-Host "[停止] 完成。已停止端口：$($Ports -join ', ')" -ForegroundColor Green
