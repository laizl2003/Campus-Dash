<#
    CampusDash 一键启动 / 停止 / 重启 / 状态 / 重置 MQ

    用法（在仓库根目录 d:\Code\Campus_PaoTui\campus-dash-1 执行）：

        .\dev.ps1                    # 启动全部（默认 start，含 worker）
        .\dev.ps1 start              # 启动中间件 + 后端 + 前端 + worker，并等到健康检查通过
        .\dev.ps1 start -NoWorker    # 同上，但不起 worker
        .\dev.ps1 restart            # 停服务 -> 重新编译 -> 再启动（改了后端/worker 代码用这个）
        .\dev.ps1 status             # 查看容器、端口、worker 状态
        .\dev.ps1 stop               # 停掉后端/前端/worker 进程并停止中间件
        .\dev.ps1 reset-mq           # 删除并重建 4 个 topic（broker 被重建过、类型不对时用）

    脚本做的事：检查 Docker 引擎 / JDK 21 / npm
    -> 检查代码是否已编译，陈旧（含 worker 未打包）则自动 mvn install -DskipTests
    -> docker compose up -d 并等 healthy -> 幂等初始化 topic 与消费组
    -> 在独立窗口分别启动后端(8080)、前端(5173)、worker
    -> 轮询 /api/health 直到 UP，最后打印访问地址。
    已经在运行的部分会自动跳过，不会重复启动。
#>
[CmdletBinding()]
param(
    [ValidateSet('start', 'stop', 'restart', 'status', 'reset-mq')]
    [string]$Action = 'start',

    # start 时跳过 worker（只想要前后端时用）
    [switch]$NoWorker
)

# 注意：这里刻意不用 $ErrorActionPreference='Stop'。
# 外部命令（docker/npm/mvn）往 stderr 写内容时，Stop 会把它当成终止错误，
# 导致正常输出（如 maven 的 INFO）也被打断。统一改为显式检查 $LASTEXITCODE。
$ErrorActionPreference = 'Continue'

# ---------------- 本机路径与端口（换机器只改这一段） ----------------
$Root        = $PSScriptRoot
$BackendDir  = Join-Path $Root 'campus-dash-backend'
$DockerDir   = Join-Path $BackendDir 'docker'
$FrontendDir = Join-Path $Root 'campus-dash-frontend'
$JdkHome     = 'D:\develop\jdk21'
$DockerBin   = 'C:\Program Files\Docker\Docker\resources\bin'

$BackendPort  = 8080
$FrontendPort = 5173

# 本地 Maven 仓库与模块版本：用于判断"源码是否比已安装的 jar 新"
$MavenRepo     = 'D:\develop\maven_repo'
$ModuleVersion = '1.0.0-SNAPSHOT'

# worker 的可执行 jar（由 mvn install 的 spring-boot repackage 产出）
$WorkerJar = Join-Path $BackendDir "dash-worker\target\dash-worker-$ModuleVersion.jar"

# `mvn -pl dash-bootstrap spring-boot:run` 只编译 dash-bootstrap 自己，其余模块
# 从本地仓库取已安装的 jar。所以下面这些模块"改完必须先重装"，否则跑的是旧代码。
# （dash-bootstrap 的改动本来就会被编译；dash-bench 不在应用的 classpath 上，都不列入。）
$WatchedModules = @('dash-shared', 'dash-domain', 'dash-application', 'dash-infrastructure', 'dash-presentation', 'dash-worker')

$Containers = @('dash-mysql', 'dash-redis', 'dash-rmqnamesrv', 'dash-rmqbroker')
$Topics = @(
    @{ Name = 'errand-confirm-timeout'; Type = 'DELAY' }
    @{ Name = 'errand-auto-settle';     Type = 'DELAY' }
    @{ Name = 'errand-fund-event';      Type = 'TRANSACTION' }
    @{ Name = 'errand-cache-evict';     Type = 'DELAY' }
)
$Groups = @('dash-timeout-consumer', 'dash-autosettle-consumer', 'dash-fund-event-consumer', 'dash-cache-evict-consumer')

if (Test-Path $DockerBin) { $env:Path = "$DockerBin;$env:Path" }

# ---------------- 输出与工具函数 ----------------
function Write-Step([string]$Msg) { Write-Host "`n==> $Msg" -ForegroundColor Cyan }
function Write-Ok([string]$Msg)   { Write-Host "    $Msg" -ForegroundColor Green }
function Write-Note([string]$Msg) { Write-Host "    $Msg" -ForegroundColor Yellow }

function Test-DockerEngine {
    $null = & docker info 2>&1
    return ($LASTEXITCODE -eq 0)
}

function Get-PortOwner([int]$Port) {
    $conn = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
    if ($conn) { return $conn[0].OwningProcess }
    return $null
}

function Test-BackendHealthy {
    try { return (Invoke-WebRequest "http://localhost:$BackendPort/api/health" -TimeoutSec 3 -UseBasicParsing).StatusCode -eq 200 }
    catch { return $false }
}

# worker 没有 HTTP 端口，只能靠命令行里的 jar 名匹配进程。
# 注意：PowerShell 函数返回时会把数组展开，只匹配到 1 个进程时调用方拿到的是单个对象，
# 而 CimInstance 的 .Count 取不到值（多进程时反而是数组、正常）。所以调用方必须写成
# @(Get-WorkerProcesses)，强制包成数组再用 .Count。
function Get-WorkerProcesses {
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -and $_.CommandLine -like '*dash-worker*' }
}

function Stop-Worker {
    $procs = @(Get-WorkerProcesses)
    if ($procs.Count -eq 0) { Write-Ok 'worker 本就未运行'; return }
    foreach ($p in $procs) {
        Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
        Write-Ok "已停止 worker（PID $($p.ProcessId)）"
    }
}

function Wait-ContainersHealthy([int]$TimeoutSec = 180) {
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        $pending = @()
        foreach ($name in $Containers) {
            $state = (& docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' $name 2>$null)
            if ($state -ne 'healthy') { $pending += "$name=$state" }
        }
        if ($pending.Count -eq 0) { return $true }
        Start-Sleep -Seconds 3
    }
    return $false
}

function Invoke-MqAdmin([string]$MqArgs) {
    $null = & docker exec dash-rmqbroker sh -c "cd /home/rocketmq/rocketmq-*/bin && sh mqadmin $MqArgs" 2>&1
}

function Initialize-Mq([switch]$Hard) {
    if ($Hard) {
        foreach ($t in $Topics) { Invoke-MqAdmin "deleteTopic -n rmqnamesrv:9876 -c DefaultCluster -t $($t.Name)" }
        Start-Sleep -Seconds 3
    }
    foreach ($t in $Topics) {
        Invoke-MqAdmin "updateTopic -n rmqnamesrv:9876 -c DefaultCluster -t $($t.Name) -a +message.type=$($t.Type)"
    }
    foreach ($g in $Groups) {
        Invoke-MqAdmin "updateSubGroup -n rmqnamesrv:9876 -c DefaultCluster -g $g"
    }
    Write-Ok "topic 与消费组已就绪（4 个 topic / 4 个消费组）"
}

# 在独立控制台窗口里跑一段脚本。用 -EncodedCommand 传参，彻底避开引号转义问题。
function Start-Console([string]$Title, [string]$Body) {
    $script  = "`$Host.UI.RawUI.WindowTitle = '$Title'; $Body"
    $encoded = [Convert]::ToBase64String([System.Text.Encoding]::Unicode.GetBytes($script))
    Start-Process -FilePath 'powershell.exe' -ArgumentList @('-NoExit', '-EncodedCommand', $encoded) | Out-Null
}

# ---------------- 编译 ----------------
# 返回"源码比本地仓库 jar 新"的模块列表。这是按文件时间戳判断的近似手段：
# IDE 里保存一下文件（哪怕内容没改）也会刷新时间戳，可能有误判。
function Get-StaleModules {
    $stale = @()
    foreach ($m in $WatchedModules) {
        $srcDir = Join-Path $BackendDir "$m\src\main"
        $jar    = Join-Path $MavenRepo "com\campusdash\$m\$ModuleVersion\$m-$ModuleVersion.jar"
        if (-not (Test-Path $srcDir)) { continue }
        if (-not (Test-Path $jar)) { $stale += "$m(未安装)"; continue }
        $newest = Get-ChildItem $srcDir -Recurse -File | Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if ($newest -and $newest.LastWriteTime -gt (Get-Item $jar).LastWriteTime) { $stale += $m }
    }
    return $stale
}

# 需要重新编译的原因列表：worker 可执行 jar 缺失，或某个被监视模块的源码比仓库 jar 新。
# 误判的代价只是多编译一次（无改动时全量约 12 秒），换来的是绝不会跑到旧代码，
# 所以 start 直接据此自动重编，而不是只提醒。
function Get-BuildReasons {
    $reasons = @()
    if (-not (Test-Path $WorkerJar)) { $reasons += 'dash-worker 可执行 jar 不存在' }
    $reasons += Get-StaleModules
    return $reasons
}

function Invoke-Build {
    Write-Note '执行 mvn install -DskipTests（首次或改了公共模块会久一些，输出较长属正常）'
    $env:JAVA_HOME = $JdkHome
    Push-Location $BackendDir
    try { & mvn install -DskipTests } finally { Pop-Location }
    if ($LASTEXITCODE -ne 0) { throw "  ✗ 编译失败（exit $LASTEXITCODE），先看上面的报错再重试" }
    Write-Ok '编译完成，已安装到本地仓库（worker 可执行 jar 也已重新打包）'
}

# ---------------- start ----------------
function Invoke-Start {
    Write-Step '1/6 检查前置条件'
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw '  ✗ 找不到 docker 命令，请确认已安装 Docker Desktop' }
    if (-not (Test-DockerEngine)) { throw '  ✗ Docker 引擎没起来：请先启动 Docker Desktop（托盘鲸鱼图标变绿）后重试' }
    if (-not (Test-Path (Join-Path $JdkHome 'bin\java.exe'))) { throw "  ✗ 找不到 JDK 21：$JdkHome（改脚本顶部的 `$JdkHome）" }
    if (-not (Get-Command npm -ErrorAction SilentlyContinue)) { throw '  ✗ 找不到 npm，请确认已安装 Node 18+' }
    Write-Ok 'Docker 引擎 / JDK 21 / npm 均可用'

    Write-Step '2/6 检查代码是否已编译'
    $reasons = @(Get-BuildReasons)
    if ($reasons.Count -gt 0) {
        Write-Note "需要重新编译：$($reasons -join ', ')"
        Invoke-Build
    } else {
        Write-Ok '源码与本地仓库的 jar 一致，跳过编译'
    }

    Write-Step '3/6 启动中间件（MySQL 3307 / Redis 6380 / RocketMQ 8081）'
    Push-Location $DockerDir
    try { $null = & docker compose up -d 2>&1 } finally { Pop-Location }
    if (Wait-ContainersHealthy 180) { Write-Ok '4 个容器全部 healthy' }
    else { Write-Note '等待 healthy 超时，请用 .\dev.ps1 status 检查' }

    Write-Step '4/6 初始化 MQ（幂等，重复执行无副作用）'
    Initialize-Mq

    Write-Step '5/6 启动后端(8080) / 前端(5173) / worker'
    $backendOwner = Get-PortOwner $BackendPort
    if ($backendOwner) {
        Write-Note "端口 $BackendPort 已被 PID $backendOwner 占用，跳过后端启动"
        if (-not (Test-BackendHealthy)) { Write-Note "但 /api/health 不通，可能不是 CampusDash；要重启用 .\dev.ps1 restart" }
    } else {
        Start-Console 'CampusDash Backend' "`$env:JAVA_HOME = '$JdkHome'; Set-Location '$BackendDir'; mvn -pl dash-bootstrap spring-boot:run"
        Write-Ok '后端已在新窗口启动（Java 21）'
    }

    $frontendOwner = Get-PortOwner $FrontendPort
    if ($frontendOwner) {
        Write-Note "端口 $FrontendPort 已被 PID $frontendOwner 占用，跳过前端启动"
    } else {
        if (-not (Test-Path (Join-Path $FrontendDir 'node_modules'))) {
            Write-Note '前端依赖未安装，先执行 npm install ...'
            Push-Location $FrontendDir
            try { $null = & npm install --registry=https://registry.npmmirror.com/ --replace-registry-host=always --no-audit --no-fund 2>&1 } finally { Pop-Location }
        }
        Start-Console 'CampusDash Frontend' "Set-Location '$FrontendDir'; npm run dev"
        Write-Ok '前端已在新窗口启动（Vite）'
    }

    if ($NoWorker) {
        Write-Note '按 -NoWorker 跳过 worker'
    } else {
        $workerProcs = @(Get-WorkerProcesses)
        if ($workerProcs.Count -gt 0) {
            Write-Note "worker 已在运行（PID $(($workerProcs | ForEach-Object { $_.ProcessId }) -join ', ')），跳过"
        } else {
            # 用 jdk21 的绝对路径，避免 PATH 上的 java 还是 jdk11 导致 worker 起不来
            Start-Console 'CampusDash Worker' "Set-Location '$BackendDir'; & '$JdkHome\bin\java.exe' -jar '$WorkerJar'"
            Write-Ok 'worker 已在新窗口启动'
        }
    }

    Write-Step '6/6 等待后端健康检查'
    $up = $false
    for ($i = 0; $i -lt 40; $i++) {
        if (Test-BackendHealthy) { $up = $true; break }
        Start-Sleep -Seconds 3
    }
    if ($up) { Write-Ok '/api/health 返回 200，status=UP' }
    else { Write-Note '后端还没起来：看后端窗口的日志，或 .\dev.ps1 status' }

    Write-Host ''
    Write-Host '  访问地址' -ForegroundColor Cyan
    Write-Host "    前端页面      http://localhost:$FrontendPort"
    Write-Host "    后端健康检查  http://localhost:$BackendPort/api/health"
    Write-Host '    演示身份      1001 发单人 / 2001、2002 跑腿 / 9001 仲裁员'
    Write-Host ''
    if ($NoWorker) {
        Write-Host '  worker        未启动（-NoWorker）：超时流转 / 自动结算 / 站内消息落库 不会发生' -ForegroundColor Yellow
    } else {
        Write-Host '  worker        独立窗口运行中：超时流转 / 自动结算 / 资金事件落库 / 消息重发' -ForegroundColor Cyan
    }
    Write-Host ''
    Write-Host '  停止：.\dev.ps1 stop' -ForegroundColor Cyan
}

# ---------------- stop ----------------
function Invoke-Stop {
    Write-Step '停止应用进程（后端 / 前端 / worker）'
    foreach ($port in @($BackendPort, $FrontendPort)) {
        $owner = Get-PortOwner $port
        if ($owner) {
            Stop-Process -Id $owner -Force
            Write-Ok "已停止端口 $port（PID $owner）"
        } else {
            Write-Ok "端口 $port 本就空闲"
        }
    }
    Stop-Worker

    Write-Step '停止中间件（保留数据）'
    Push-Location $DockerDir
    try { $null = & docker compose stop 2>&1 } finally { Pop-Location }
    Write-Ok '中间件已停止；下次 start 会自动恢复'

    Write-Host ''
    Write-Note '要关 Docker 引擎本身：托盘鲸鱼图标右键 → Quit Docker Desktop'
    Write-Note '注意：用了 docker compose down 会丢掉 RocketMQ 的 topic，下次 start 后需执行 .\dev.ps1 reset-mq'
}

# ---------------- status ----------------
function Invoke-Status {
    Write-Step '中间件容器'
    if (Test-DockerEngine) {
        Push-Location $DockerDir
        try { & docker compose ps -a --format 'table {{.Name}}\t{{.Status}}\t{{.Ports}}' } finally { Pop-Location }
    } else {
        Write-Note 'Docker 引擎未启动（先启动 Docker Desktop，托盘鲸鱼图标变绿）'
    }

    Write-Step '应用进程与健康'
    foreach ($port in @($BackendPort, $FrontendPort)) {
        $owner = Get-PortOwner $port
        if ($owner) { Write-Ok "端口 $port 在监听（PID $owner）" } else { Write-Note "端口 $port 空闲" }
    }

    $workerProcs = @(Get-WorkerProcesses)
    if ($workerProcs.Count -gt 0) {
        Write-Ok "worker 在运行（PID $(($workerProcs | ForEach-Object { $_.ProcessId }) -join ', ')）"
    } else {
        Write-Note 'worker 未运行（超时流转 / 自动结算 / 站内消息落库 不会发生）'
    }

    if (Test-BackendHealthy) {
        try {
            $body = (Invoke-WebRequest "http://localhost:$BackendPort/api/health" -TimeoutSec 3 -UseBasicParsing).Content
            Write-Ok "后端健康：$body"
        } catch { }
    } else {
        Write-Note '后端 /api/health 不通（未启动或还在启动中）'
    }

    try {
        $null = Invoke-WebRequest "http://localhost:$FrontendPort/" -TimeoutSec 3 -UseBasicParsing
        Write-Ok '前端页面可访问'
    } catch {
        Write-Note '前端页面不可访问（未启动）'
    }

    Write-Step '编译状态'
    $reasons = @(Get-BuildReasons)
    if ($reasons.Count -gt 0) {
        Write-Note "需要重新编译：$($reasons -join ', ')"
    } else {
        Write-Ok '源码与本地仓库的 jar 一致'
    }
    if (Test-Path $WorkerJar) {
        $info = Get-Item $WorkerJar
        Write-Ok ("worker jar 已打包（{0:N1} MB，{1}）" -f ($info.Length / 1MB), $info.LastWriteTime)
    } else {
        Write-Note 'worker jar 不存在，需要编译'
    }
}

# ---------------- reset-mq ----------------
function Invoke-ResetMq {
    if (-not (Test-DockerEngine)) { throw '  ✗ Docker 引擎未启动' }
    $state = (& docker inspect -f '{{.State.Status}}' dash-rmqbroker 2>$null)
    if ($state -ne 'running') { throw '  ✗ dash-rmqbroker 未运行，请先执行 .\dev.ps1 start' }

    Write-Step '删除并重建 4 个 topic'
    Write-Note '会清掉 broker 里积压的未投递消息（本地开发环境可接受）'
    Initialize-Mq -Hard
    Write-Ok '完成。已订阅的消费者会自动重新订阅'
}

# ---------------- restart ----------------
function Invoke-Restart {
    Write-Step '1/3 停止应用进程（中间件保持运行，不重启容器）'
    foreach ($port in @($BackendPort, $FrontendPort)) {
        $owner = Get-PortOwner $port
        if ($owner) {
            Stop-Process -Id $owner -Force
            Write-Ok "已停止端口 $port（PID $owner）"
        } else {
            Write-Ok "端口 $port 本就空闲"
        }
    }
    Stop-Worker

    Write-Step '2/3 重新编译（把改动的业务代码与 worker 重新打包安装）'
    Invoke-Build

    Write-Step '3/3 重新启动'
    Invoke-Start
}

# ---------------- 入口 ----------------
switch ($Action) {
    'start'    { Invoke-Start }
    'stop'     { Invoke-Stop }
    'restart'  { Invoke-Restart }
    'status'   { Invoke-Status }
    'reset-mq' { Invoke-ResetMq }
}
