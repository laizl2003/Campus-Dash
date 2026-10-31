<#
.SYNOPSIS
    CampusDash 本地一键构建 / 启动 / 运维。

.DESCRIPTION
    改完前后端代码后，只需要在仓库根目录运行 .\start.ps1：
      1. 重新打包后端 fat jar（dash-bootstrap + dash-worker）
      2. 重新构建前端静态产物（tsc + vite build）
      3. 重建镜像并用 docker compose 启动
      4. 等 /api/health 通过，打印访问地址与容器状态

    不想构建时用 -Build none 直接重启；只想看状态用 -Status。

.PARAMETER Build
    all（默认，前后端都构建）| api（只后端）| web（只前端）| none（不构建，只重启）

.PARAMETER Down
    停止整套服务（保留 MySQL 数据卷）。

.PARAMETER Purge
    配合 -Down 使用：连同数据卷一起删除（会清空数据库，慎用）。

.PARAMETER Status
    只打印容器状态与健康检查结果，不做任何构建或启动。

.PARAMETER Logs
    启动完成后跟随 app / worker / web 的日志（Ctrl+C 退出，不影响容器）。

.PARAMETER StartDocker
    Docker 引擎没启动时，尝试拉起 Docker Desktop 并等待就绪。

.PARAMETER TimeoutSeconds
    等待应用健康的超时时间，默认 180 秒。

.EXAMPLE
    .\start.ps1
    改完代码后的日常：重建前后端 + 重启 + 健康检查。

.EXAMPLE
    .\start.ps1 -Build web
    只改了前端，跳过 Maven 打包。

.EXAMPLE
    .\start.ps1 -Status
    看看现在跑得怎么样。
#>
# 注意：本文件必须以「UTF-8 with BOM」保存。
# Windows PowerShell 5.1 读无 BOM 的脚本会按 ANSI(GBK) 解码，中文注释会把引号吃掉，报一堆语法错误。
#Requires -Version 5.1
[CmdletBinding()]
param(
    [ValidateSet('all', 'api', 'web', 'none')]
    [string]$Build = 'all',

    [switch]$Down,
    [switch]$Purge,
    [switch]$Status,
    [switch]$Logs,
    [switch]$StartDocker,

    [int]$TimeoutSeconds = 180
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$script:root = $PSScriptRoot
$script:backendDir = Join-Path $script:root 'campus-dash-backend'
$script:frontendDir = Join-Path $script:root 'campus-dash-frontend'
# Agent 前端源码独立放在 agent/agent-frontend，由主前端 vite alias @agent 引用；
# 构建仍在 campus-dash-frontend 里执行，产物统一进 dist。
$script:agentFrontendDir = Join-Path $script:root 'agent\agent-frontend'
$script:dockerDir = Join-Path $script:backendDir 'docker'
$script:baseUrl = 'http://localhost:8088'
$script:composeFiles = @('-f', 'docker-compose.yaml', '-f', 'docker-compose.full.yaml')

function Write-Step {
    param([string]$Text)
    Write-Host ""
    Write-Host "==> $Text" -ForegroundColor Cyan
}

function Write-Ok {
    param([string]$Text)
    Write-Host "    $Text" -ForegroundColor Green
}

function Write-Warn {
    param([string]$Text)
    Write-Host "    $Text" -ForegroundColor Yellow
}

function Invoke-Tool {
    param(
        [string]$Title,
        [string]$Exe,
        [string[]]$Arguments,
        [string]$WorkDir
    )

    Write-Step $Title
    $log = Join-Path $env:TEMP ('campusdash-' + [guid]::NewGuid().ToString('N') + '.log')
    $watch = [Diagnostics.Stopwatch]::StartNew()
    Push-Location $WorkDir
    try {
        & $Exe @Arguments *> $log
        $code = $LASTEXITCODE
    }
    finally {
        Pop-Location
    }
    $watch.Stop()

    if ($code -ne 0) {
        Write-Host ("    失败（退出码 {0}，耗时 {1:N1}s）" -f $code, $watch.Elapsed.TotalSeconds) -ForegroundColor Red
        Get-Content $log -Tail 40 | ForEach-Object { Write-Host "    $_" -ForegroundColor DarkGray }
        Remove-Item $log -ErrorAction SilentlyContinue
        throw "$Title 失败，请按上面的日志排查"
    }

    Write-Ok ("完成（{0:N1}s）" -f $watch.Elapsed.TotalSeconds)
    Remove-Item $log -ErrorAction SilentlyContinue
}

function Test-DockerEngine {
    & docker info --format '{{.ServerVersion}}' *> $null
    return ($LASTEXITCODE -eq 0)
}

function Wait-DockerEngine {
    param([int]$Seconds = 180)

    if (Test-DockerEngine) { return $true }
    if (-not $StartDocker) {
        Write-Warn 'Docker 引擎未运行。启动 Docker Desktop 后重试，或加 -StartDocker 让脚本自己拉起。'
        return $false
    }

    $exe = 'C:\Program Files\Docker\Docker\Docker Desktop.exe'
    if (-not (Test-Path $exe)) {
        Write-Warn "找不到 Docker Desktop：$exe"
        return $false
    }

    Write-Step '启动 Docker Desktop'
    Start-Process $exe | Out-Null
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 3
        if (Test-DockerEngine) {
            Write-Ok 'Docker 就绪'
            return $true
        }
    }

    Write-Warn "等待 Docker 超时（$Seconds 秒）"
    return $false
}

function Invoke-Compose {
    param([string[]]$Arguments)

    Push-Location $script:dockerDir
    try {
        & docker compose @script:composeFiles @Arguments
        $script:lastComposeExit = $LASTEXITCODE
    }
    finally {
        Pop-Location
    }
}

function Wait-AppHealthy {
    param([int]$Seconds)

    Write-Step "等待应用健康（最多 $Seconds 秒）"
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $response = Invoke-RestMethod -Uri "$script:baseUrl/api/health" -TimeoutSec 3
            if ($response.code -eq 'OK') {
                Write-Ok '应用已就绪'
                return $true
            }
        }
        catch {
        }
        Start-Sleep -Seconds 2
    }

    Write-Warn '健康检查超时，最近的应用日志：'
    & docker logs dash-app --tail 40 2>&1 | ForEach-Object { Write-Host "    $_" -ForegroundColor DarkGray }
    return $false
}

function Show-Summary {
    Write-Step '容器状态'
    Invoke-Compose @('ps')
    Write-Host ""
    Write-Host '    前端入口  ' -NoNewline
    Write-Host $script:baseUrl -ForegroundColor Green
    Write-Host '    健康检查  ' -NoNewline
    Write-Host "$script:baseUrl/api/health" -ForegroundColor DarkGray
    Write-Host '    演示身份  1001 发单 / 2001、2002 跑腿 / 9001 仲裁' -ForegroundColor DarkGray
    Write-Host '    跟随日志  ' -NoNewline
    Write-Host '.\start.ps1 -Logs' -ForegroundColor DarkGray
    Write-Host '    停止服务  ' -NoNewline
    Write-Host '.\start.ps1 -Down' -ForegroundColor DarkGray
}

# ------------------------------------------------------------------ 主流程
if ($Status) {
    if (-not (Wait-DockerEngine -Seconds 5)) { exit 1 }
    Write-Step '健康检查'
    try {
        $health = Invoke-RestMethod -Uri "$script:baseUrl/api/health" -TimeoutSec 5
        Write-Ok "code=$($health.code) status=$($health.data.status)"
    }
    catch {
        Write-Warn "不可用：$($_.Exception.Message)"
    }
    Show-Summary
    exit 0
}

if ($Down) {
    if (-not (Wait-DockerEngine -Seconds 5)) { exit 1 }
    $downArgs = @('down')
    if ($Purge) {
        Write-Warn '将删除 MySQL 数据卷，数据库内容会丢失'
        $downArgs += '-v'
    }
    Write-Step '停止服务'
    Invoke-Compose $downArgs
    if ($script:lastComposeExit -ne 0) { throw '停止失败' }
    Write-Ok '已停止'
    exit 0
}

if (-not (Wait-DockerEngine)) { exit 1 }

$started = Get-Date
$buildTargets = @()
if ($Build -eq 'all') { $buildTargets = @('app', 'web') }
elseif ($Build -eq 'api') { $buildTargets = @('app') }
elseif ($Build -eq 'web') { $buildTargets = @('web') }

if ($buildTargets -contains 'app') {
    Invoke-Tool -Title '打包后端（dash-bootstrap + dash-worker）' -Exe 'mvn' -Arguments @('-pl', 'dash-bootstrap,dash-worker', '-am', 'package', '-DskipTests') -WorkDir $script:backendDir
}

if ($buildTargets -contains 'web') {
    if (-not (Test-Path (Join-Path $script:frontendDir 'node_modules'))) {
        Invoke-Tool -Title '安装前端依赖（首次）' -Exe 'npm' -Arguments @('ci') -WorkDir $script:frontendDir
    }
    if (-not (Test-Path (Join-Path $script:agentFrontendDir 'src'))) {
        throw "找不到 Agent 前端源码目录：$script:agentFrontendDir"
    }
    # Agent 组件在 agent/agent-frontend/src，通过 @agent 别名参与同一次构建
    Invoke-Tool -Title '构建前端（含 Agent 悬浮球，tsc + vite build）' -Exe 'npm' -Arguments @('run', 'build') -WorkDir $script:frontendDir
}

if ($buildTargets.Count -gt 0) {
    Write-Step '重建镜像'
    $buildArgs = @('build') + $buildTargets
    Invoke-Compose $buildArgs
    if ($script:lastComposeExit -ne 0) { throw '镜像构建失败' }
    $joined = $buildTargets -join ', '
    Write-Ok "已构建：$joined"
}

Write-Step '启动 / 更新容器'
# 只点名要跑的服务：mq-init 是"一次性任务"，如果已经成功退出就不再重跑，
# 否则每次 up 都会重建它、白等十几秒
Invoke-Compose @('up', '-d', 'app', 'worker', 'web')
if ($script:lastComposeExit -ne 0) { throw '容器启动失败' }
Write-Ok 'compose 已收敛'

$healthy = Wait-AppHealthy -Seconds $TimeoutSeconds
Show-Summary

$elapsed = (Get-Date) - $started
Write-Host ""
Write-Host ("    总耗时    {0:N1} 秒" -f $elapsed.TotalSeconds) -ForegroundColor DarkGray

if (-not $healthy) { exit 1 }

if ($Logs) {
    Write-Step '跟随日志（Ctrl+C 退出）'
    Invoke-Compose @('logs', '-f', '--tail', '30', 'app', 'worker', 'web')
}

exit 0
