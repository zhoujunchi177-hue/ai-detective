# 启动 Vue 3 前端开发服务器（默认端口 5173）。
# 首次运行会自动执行 npm install。
param(
    [int]$Port = 5173,
    [switch]$SkipInstall
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$frontend = Join-Path $root 'frontend'

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

if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
    Write-Host "[前端] 找不到 node，请先安装 Node.js 18+ 并加入 PATH。" -ForegroundColor Red
    exit 1
}
if (-not (Test-Path (Join-Path $frontend 'package.json'))) {
    Write-Host "[前端] 找不到 $frontend\package.json" -ForegroundColor Red
    exit 1
}

Set-Location $frontend

if (-not $SkipInstall -and -not (Test-Path (Join-Path $frontend 'node_modules'))) {
    Write-Host "[前端] 未检测到 node_modules，正在执行 npm install ..." -ForegroundColor Yellow
    npm install
    if ($LASTEXITCODE -ne 0) {
        Write-Host "[前端] npm install 失败。" -ForegroundColor Red
        exit 1
    }
}

# 端口预检：vite 默认在端口被占用时会**悄悄换到下一个端口**（5173 -> 5174），
# 但脚本仍打印 5173，于是你打开的其实是另一个实例（或什么都没有），
# 表现为「页面能开但接口连不上」这类难排查的现象。这里改成直接报错退出。
if (Test-TcpPort '127.0.0.1' $Port) {
    Write-Host "[前端] 端口 $Port 已被占用，无法启动。" -ForegroundColor Red
    Write-Host "[前端] 先关掉占用该端口的程序（可能是上一次没关掉的前端），" -ForegroundColor Yellow
    Write-Host "[前端] 或改用其他端口：npm run dev -- --port 5180" -ForegroundColor Yellow
    Write-Host "[前端] 查看占用者：netstat -ano | findstr :$Port" -ForegroundColor Yellow
    exit 1
}

Write-Host "[前端] 启动 Vite 开发服务器，端口 $Port" -ForegroundColor Green
Write-Host "[前端] 访问 http://127.0.0.1:$Port （/api 会代理到后端 8080）"

$env:PORT = "$Port"
# --strictPort：端口被占用时直接失败，绝不悄悄换端口。
npm run dev -- --port $Port --strictPort
