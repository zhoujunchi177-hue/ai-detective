# 启动本项目自带的 MySQL 实例。
# 注意：本项目使用的数据库是项目内的独立实例，端口 3307，数据目录 .runtime/mysql/data，
# 与系统里可能存在的 3306 实例不是同一个，请勿混淆。
param(
    [int]$Port = 3307,
    [string]$MysqldPath = ''
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$dataDir = Join-Path $root '.runtime\mysql\data'
$logDir = Join-Path $root '.runtime\mysql'

function Test-TcpPort([string]$TargetHost, [int]$TargetPort) {
    try {
        $client = New-Object System.Net.Sockets.TcpClient
        $client.Connect($TargetHost, $TargetPort)
        $client.Close()
        return $true
    } catch {
        return $false
    }
}

function Resolve-Mysqld([string]$Explicit) {
    if ($Explicit -and (Test-Path $Explicit)) { return $Explicit }
    $candidates = @(
        'C:\Program Files\MySQL\MySQL Server 8.4\bin\mysqld.exe',
        'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqld.exe',
        'C:\Program Files\MySQL\MySQL Server 5.7\bin\mysqld.exe'
    )
    foreach ($path in $candidates) {
        if (Test-Path $path) { return $path }
    }
    $cmd = Get-Command mysqld -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    return ''
}

if (Test-TcpPort '127.0.0.1' $Port) {
    Write-Host "[MySQL] 端口 $Port 已在运行，跳过启动。" -ForegroundColor Green
    exit 0
}

$mysqld = Resolve-Mysqld $MysqldPath
if (-not $mysqld) {
    Write-Host "[MySQL] 找不到 mysqld.exe，请用 -MysqldPath 指定完整路径。" -ForegroundColor Red
    exit 1
}
if (-not (Test-Path $dataDir)) {
    Write-Host "[MySQL] 找不到数据目录：$dataDir" -ForegroundColor Red
    Write-Host "[MySQL] 若首次使用，请先按 README 用 schema.sql / data.sql 初始化数据库。" -ForegroundColor Yellow
    exit 1
}

if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir | Out-Null }

Write-Host "[MySQL] 正在启动：$mysqld"
Write-Host "[MySQL] 数据目录：$dataDir"
Write-Host "[MySQL] 端口：$Port"

Start-Process -FilePath $mysqld `
    -ArgumentList @("--datadir=$dataDir", "--port=$Port", '--console') `
    -RedirectStandardOutput (Join-Path $logDir 'start.out') `
    -RedirectStandardError (Join-Path $logDir 'start.err') `
    -WindowStyle Hidden

for ($i = 1; $i -le 30; $i++) {
    Start-Sleep -Seconds 1
    if (Test-TcpPort '127.0.0.1' $Port) {
        Write-Host "[MySQL] 已就绪：127.0.0.1:$Port" -ForegroundColor Green
        exit 0
    }
}

Write-Host "[MySQL] 启动超时（30 秒），请查看 $logDir\start.err" -ForegroundColor Red
exit 1
