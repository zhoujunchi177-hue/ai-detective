# 启动 Spring Boot 后端（默认端口 8080）。
# 会自动定位 JDK、设置 JAVA_HOME，并把数据库指向项目自带的 MySQL 实例（端口 3307）。
# 如果已经设置过 DEEPSEEK_API_KEY 环境变量，会原样透传给后端；脚本不会写入或记录任何 Key。
param(
    [int]$ServerPort = 8080,
    [int]$DbPort = 3307,
    [string]$DbName = 'mindtrace',
    [string]$DbUser = 'root',
    [string]$DbPassword = '',
    [string]$JavaHome = ''
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$backend = Join-Path $root 'backend'

function Resolve-JavaHome([string]$Explicit) {
    if ($Explicit -and (Test-Path (Join-Path $Explicit 'bin\java.exe'))) { return $Explicit }
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) { return $env:JAVA_HOME }
    $candidates = @(
        'D:\jdk',
        'C:\Program Files\Java\jdk-21',
        'C:\Program Files\Java\jdk-17',
        'C:\Program Files\Eclipse Adoptium\jdk-21*'
    )
    foreach ($path in $candidates) {
        $resolved = Get-ChildItem -Path $path -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($resolved -and (Test-Path (Join-Path $resolved.FullName 'bin\java.exe'))) { return $resolved.FullName }
        if ($path -notmatch '\*' -and (Test-Path (Join-Path $path 'bin\java.exe'))) { return $path }
    }
    $cmd = Get-Command java -ErrorAction SilentlyContinue
    if ($cmd) {
        $bin = Split-Path -Parent $cmd.Source
        $home = Split-Path -Parent $bin
        if (Test-Path (Join-Path $home 'bin\java.exe')) { return $home }
    }
    return ''
}

$resolvedJava = Resolve-JavaHome $JavaHome
if (-not $resolvedJava) {
    Write-Host "[后端] 找不到 JDK，请用 -JavaHome 指定，例如 -JavaHome 'D:\jdk'。" -ForegroundColor Red
    exit 1
}

$env:JAVA_HOME = $resolvedJava
$env:PATH = (Join-Path $resolvedJava 'bin') + ';' + $env:PATH
$env:SERVER_PORT = "$ServerPort"
$env:DB_URL = "jdbc:mysql://127.0.0.1:$DbPort/$DbName?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false"
$env:DB_USERNAME = $DbUser
$env:DB_PASSWORD = $DbPassword

Write-Host "[后端] JAVA_HOME = $resolvedJava"
Write-Host "[后端] 数据库 = 127.0.0.1:$DbPort/$DbName (user=$DbUser)"
Write-Host "[后端] 端口 = $ServerPort"
if ($env:DEEPSEEK_API_KEY) {
    Write-Host "[后端] 已检测到 DEEPSEEK_API_KEY，AI 功能将调用真实 DeepSeek API。" -ForegroundColor Green
} else {
    Write-Host "[后端] 未检测到 DEEPSEEK_API_KEY，AI 功能将使用本地降级结果。" -ForegroundColor Yellow
}

Set-Location $backend
& (Join-Path $backend 'mvnw.cmd') spring-boot:run
