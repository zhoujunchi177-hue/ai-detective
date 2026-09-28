# 一键启动 MindTrace 的全部服务（MySQL 3307 / 后端 8080 / 前端 5173）。
#
# 为什么需要这个脚本：
#   start-mysql.ps1 / start-backend.ps1 / start-frontend.ps1 里，
#   **后两个是「前台常驻」进程** —— 后端的 mvnw spring-boot:run 和
#   前端的 npm run dev 都会一直占着窗口、永不返回。
#   所以「依次执行」这三个脚本，最多只能走到第二步：前端根本起不来，
#   浏览器打开 5173 自然连不上。
#
#   本脚本把后端、前端各放到**独立窗口**里跑，自己在当前窗口负责
#   等待就绪 + 做一次真实冒烟（登录 + 拉案件列表），最后打印该访问的地址。
#
# 用法：
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-all.ps1
#   想跳过冒烟：-SkipSmoke
param(
    [int]$BackendPort = 8080,
    [int]$FrontendPort = 5173,
    [int]$DbPort = 3307,
    [int]$BackendTimeoutSec = 300,
    [switch]$SkipSmoke
)

$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot

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

# PS 5.1 的 Invoke-RestMethod 在响应未显式声明 charset 时会按
# ISO-8859-1 解码 JSON，中文会变成乱码（「测试调查员」-> 「æµè¯è°æ¥å」）。
# 统一走「取原始字节 -> 按 UTF-8 解码 -> ConvertFrom-Json」。
function Invoke-JsonUtf8([string]$Url, [string]$Method = 'GET', $Body = $null, $Headers = $null) {
    $params = @{ Uri = $Url; Method = $Method; UseBasicParsing = $true; TimeoutSec = 15 }
    if ($Headers) { $params.Headers = $Headers }
    if ($Body) {
        $params.ContentType = 'application/json; charset=utf-8'
        $params.Body = [System.Text.Encoding]::UTF8.GetBytes($Body)
    }
    $resp = Invoke-WebRequest @params
    $text = [System.Text.Encoding]::UTF8.GetString($resp.RawContentStream.ToArray())
    return ($text | ConvertFrom-Json)
}

function Wait-HttpOk([string]$Url, [int]$TimeoutSec) {
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        try {
            $resp = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 5
            if ($resp.StatusCode -eq 200) { return $true }
        } catch {
        }
        Start-Sleep -Seconds 2
    }
    return $false
}

Write-Host "=== [1/4] MySQL ($DbPort) ===" -ForegroundColor Cyan
& (Join-Path $here 'start-mysql.ps1') -Port $DbPort
if (-not (Test-TcpPort '127.0.0.1' $DbPort)) {
    Write-Host "MySQL 未能就绪，终止。请查看 .runtime\mysql\start.err" -ForegroundColor Red
    exit 1
}

Write-Host "=== [2/4] 后端 ($BackendPort) —— 将在新窗口启动 ===" -ForegroundColor Cyan
if (Test-TcpPort '127.0.0.1' $BackendPort) {
    Write-Host "端口 $BackendPort 已在运行，跳过启动。" -ForegroundColor Green
} else {
    Start-Process -FilePath 'powershell.exe' -ArgumentList @(
        '-NoExit', '-NoProfile', '-ExecutionPolicy', 'Bypass',
        '-File', (Join-Path $here 'start-backend.ps1'),
        '-ServerPort', "$BackendPort", '-DbPort', "$DbPort"
    )
    Write-Host "已在新窗口启动后端。首次运行需要 Maven 编译，通常 30 秒到 2 分钟，请耐心等待 ..." -ForegroundColor Yellow
}

if (-not (Wait-HttpOk "http://127.0.0.1:$BackendPort/api/health" $BackendTimeoutSec)) {
    Write-Host "后端在 $BackendTimeoutSec 秒内未就绪。" -ForegroundColor Red
    Write-Host "常见原因：端口 $BackendPort 被占用（看后端窗口是否打印 Port already in use）、" -ForegroundColor Yellow
    Write-Host "或数据库连不上（确认 MySQL 在 $DbPort）。" -ForegroundColor Yellow
    exit 1
}
Write-Host "后端已就绪。" -ForegroundColor Green

Write-Host "=== [3/4] 前端 ($FrontendPort) —— 将在新窗口启动 ===" -ForegroundColor Cyan
if (Test-TcpPort '127.0.0.1' $FrontendPort) {
    Write-Host "端口 $FrontendPort 已在运行，跳过启动。" -ForegroundColor Green
} else {
    Start-Process -FilePath 'powershell.exe' -ArgumentList @(
        '-NoExit', '-NoProfile', '-ExecutionPolicy', 'Bypass',
        '-File', (Join-Path $here 'start-frontend.ps1'),
        '-Port', "$FrontendPort"
    )
}

if (-not (Wait-HttpOk "http://127.0.0.1:$FrontendPort/" 120)) {
    Write-Host "前端在 120 秒内未就绪，请查看前端窗口的报错。" -ForegroundColor Red
    exit 1
}
Write-Host "前端已就绪。" -ForegroundColor Green

$smokeOk = $true
if (-not $SkipSmoke) {
    Write-Host "=== [4/4] 真实冒烟（登录 + 案件列表）===" -ForegroundColor Cyan
    try {
        $loginBody = @{ username = 'demo_investigator'; password = 'demo123' } | ConvertTo-Json
        $login = Invoke-JsonUtf8 -Url "http://127.0.0.1:$BackendPort/api/auth/login" -Method 'POST' -Body $loginBody
        $token = $login.data.token
        Write-Host ("  登录成功：{0}" -f $login.data.profile.nickname) -ForegroundColor Green

        $headers = @{ Authorization = "Bearer $token" }
        $cases = Invoke-JsonUtf8 -Url "http://127.0.0.1:$BackendPort/api/cases" -Headers $headers
        Write-Host ("  案件列表：{0} 个案件" -f $cases.data.Count) -ForegroundColor Green

        $health = Invoke-JsonUtf8 -Url "http://127.0.0.1:$BackendPort/api/health"
        if ($health.data.deepSeekConfigured) {
            Write-Host "  DeepSeek：已配置，AI 走真实调用" -ForegroundColor Green
        } else {
            Write-Host "  DeepSeek：未配置，AI 走本地降级（不影响登录与进度保存）" -ForegroundColor Yellow
            Write-Host "  要启用真实 AI：setx DEEPSEEK_API_KEY ""你的Key""，然后重启后端" -ForegroundColor Yellow
        }
    } catch {
        $smokeOk = $false
        Write-Host ("  冒烟失败：{0}" -f $_.Exception.Message) -ForegroundColor Red
        Write-Host "  注意：/api/health 返回 UP 不代表数据库已连上（连接池是懒连接）。" -ForegroundColor Yellow
    }
}

Write-Host ""
if ($smokeOk) {
    Write-Host "全部就绪，请打开： http://localhost:$FrontendPort" -ForegroundColor Green
    Write-Host "演示账号：demo_investigator / demo123" -ForegroundColor Green
} else {
    Write-Host "服务已启动，但冒烟未通过，请查看上面的错误。" -ForegroundColor Red
}
Write-Host "停止全部服务： powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\stop-all.ps1" -ForegroundColor Gray
Write-Host "（后端 / 前端各占一个独立窗口，关掉那个窗口即停止对应服务）" -ForegroundColor Gray
