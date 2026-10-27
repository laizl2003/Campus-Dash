# 清理本地演示库里的测试任务（每次跑完测试/联调后执行一次）。
# 规则：
#   1. 非终态任务：托管金额退回发单人，删除任务与相关行；
#   2. 终态测试任务（settle% / refund% / api_it% / credit_% / bench% / 含"测试/联调/验证/演示"）：
#      只删业务行，保留 wallet_ledger（钱已结算，删流水会让余额对不上）。
# 用法：.\cleanup-test-data.ps1
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$sql = Join-Path $here 'cleanup-test-data.sql'
if (-not (Test-Path $sql)) { throw "找不到 $sql" }

function Mysql($statement) {
    docker exec -e MYSQL_PWD=dash123456 dash-mysql mysql -uroot campus_dash -e $statement
}

Write-Host '清理前：'
Mysql "select status,count(*) from errand group by status"

docker cp $sql dash-mysql:/tmp/cleanup-test-data.sql | Out-Null
docker exec -e MYSQL_PWD=dash123456 dash-mysql sh -c "mysql -uroot campus_dash < /tmp/cleanup-test-data.sql"
if ($LASTEXITCODE -ne 0) { throw '清理 SQL 执行失败' }

Write-Host '清理后：'
Mysql "select status,count(*) from errand group by status"
Mysql "select concat('L1 借贷差=', coalesce(sum(case when direction='DEBIT' then amount else -amount end),0)) from wallet_ledger"
Mysql "select concat('广场可见任务=', count(*)) from errand where status='PUBLISHED'"
