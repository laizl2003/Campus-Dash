#!/usr/bin/env bash
# 按轮次清理测试/压测数据。
#
# 为什么不用全表 DELETE：数据要能保留用于"改动前后同档位对比"，
# 所以清理必须精确到轮次，且绝不能碰 seed 基线数据（账户）。
# 依据是 bench_run_item 里登记的 run_id -> errand_id 关联。
#
# 用法：
#   ./cleanup.sh                          # 默认保留最近 10 轮
#   ./cleanup.sh --keep-last=3            # 只保留最近 3 轮
#   ./cleanup.sh --run=20260818-014200    # 只清指定轮次
#   ./cleanup.sh --before=2026-08-17      # 清掉该日期之前开始的轮次
#   ./cleanup.sh --scope=it               # 只清集成测试轮次（kind=IT）
#   ./cleanup.sh --all --force            # 清掉全部轮次数据（保留 seed 账户）
#   ./cleanup.sh --orphans                # 清掉无 run 归属的孤儿数据（P1 遗留，那时还没有 run_id）
set -euo pipefail

MYSQL_CONTAINER="${MYSQL_CONTAINER:-dash-mysql}"
REDIS_CONTAINER="${REDIS_CONTAINER:-dash-redis}"
DB="${DB:-campus_dash}"
# 用 MYSQL_PWD 传密码，避免每条命令都刷 "Using a password on the command line" 警告
MYSQL="docker exec -i -e MYSQL_PWD=dash123456 $MYSQL_CONTAINER mysql -uroot -D $DB -N -B"

KEEP_LAST=10
ORPHANS_ONLY=false
RUN_ID=""
BEFORE=""
SCOPE="all"
CLEAN_ALL=false
FORCE=false

for arg in "$@"; do
  case "$arg" in
    --keep-last=*) KEEP_LAST="${arg#*=}" ;;
    --run=*)       RUN_ID="${arg#*=}" ;;
    --before=*)    BEFORE="${arg#*=}" ;;
    --scope=*)     SCOPE="${arg#*=}" ;;
    --all)         CLEAN_ALL=true ;;
    --orphans)     ORPHANS_ONLY=true ;;
    --force)       FORCE=true ;;
    -h|--help)     sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "未知参数: $arg（用 --help 查看用法）" >&2; exit 1 ;;
  esac
done

# 孤儿数据清理：P1 阶段的数据没有 run_id 归属，按轮次清理永远碰不到它们，
# 必须单独提供入口，否则历史数据会永久残留、干扰后续校验统计。
if [ "$ORPHANS_ONLY" = true ]; then
  ORPHAN_IDS=$($MYSQL -e "SELECT e.id FROM errand e
                           LEFT JOIN bench_run_item i
                             ON i.entity_type='ERRAND' AND i.entity_id = e.id
                           WHERE i.id IS NULL" | tr '\n' ' ' | xargs || true)
  ORPHAN_COUNT=$(echo "$ORPHAN_IDS" | wc -w | xargs)
  echo "无 run 归属的孤儿任务数: $ORPHAN_COUNT"
  if [ "$ORPHAN_COUNT" -gt 0 ]; then
    echo "$ORPHAN_IDS" | tr ' ' '\n' | awk 'NF{printf "DEL errand:slot:{%s} errand:grabbed:{%s} errand:candidates:{%s}\n", $1, $1, $1}' \
      | docker exec -i "$REDIS_CONTAINER" redis-cli --pipe > /dev/null 2>&1 || true
    $MYSQL <<'SQL'
CREATE TEMPORARY TABLE tmp_orphan (id BIGINT PRIMARY KEY);
INSERT IGNORE INTO tmp_orphan
  SELECT e.id FROM errand e
   LEFT JOIN bench_run_item i ON i.entity_type='ERRAND' AND i.entity_id = e.id
   WHERE i.id IS NULL;
DELETE g FROM grab_record g       JOIN tmp_orphan t ON t.id = g.errand_id;
DELETE s FROM errand_status_log s JOIN tmp_orphan t ON t.id = s.errand_id;
DELETE l FROM wallet_ledger l     JOIN tmp_orphan t ON t.id = l.ref_id;
DELETE e2 FROM escrow_order e2    JOIN tmp_orphan t ON t.id = e2.errand_id;
DELETE x FROM errand x            JOIN tmp_orphan t ON t.id = x.id;
SQL
    $MYSQL -e "UPDATE wallet_account SET available = 100000, frozen = 0 WHERE owner_id = 1001 AND owner_type='USER';
               UPDATE wallet_account SET available = 0, frozen = 0 WHERE owner_type IN ('ESCROW','COMMISSION');"
    echo "孤儿数据已清理"
  fi

  # Redis 反向孤儿：DB 里已无对应任务，但 Redis 还挂着名额键（上一期遗留，TTL 长达 7 天）。
  # 注意 errand:idem:* 有 300s TTL 会自动过期，不属于需要主动清理的范围。
  echo "扫描 Redis 反向孤儿..."
  # 一次取全部 errand key 与全部存活 id，用 grep -F -v -f 做差集，
  # 避免"每个 key 查一次 MySQL"的 N 次往返（1 万条时会跑到超时）
  ALL_KEYS_FILE=$(mktemp)
  LIVE_IDS_FILE=$(mktemp)
  for pat in 'errand:slot:*' 'errand:grabbed:*' 'errand:candidates:*'; do
    docker exec "$REDIS_CONTAINER" redis-cli --scan --pattern "$pat"
  done | sed 's/.*{\([0-9]*\)}.*/\1 &/' | sort -u > "$ALL_KEYS_FILE"
  $MYSQL -e "SELECT id FROM errand" | sort -u > "$LIVE_IDS_FILE"

  REDIS_ORPHAN=0
  if [ -s "$ALL_KEYS_FILE" ]; then
    # 第一列是 id，第二列是完整 key；join 反选出 id 不在存活列表中的行
    ORPHAN_KEYS=$(awk 'NR==FNR{live[$1]=1;next} !($1 in live){print $2}' "$LIVE_IDS_FILE" "$ALL_KEYS_FILE")
    if [ -n "$ORPHAN_KEYS" ]; then
      REDIS_ORPHAN=$(printf '%s\n' "$ORPHAN_KEYS" | grep -c . || true)
      printf '%s\n' "$ORPHAN_KEYS" | awk 'NF{printf "DEL %s\n", $1}' \
        | docker exec -i "$REDIS_CONTAINER" redis-cli --pipe > /dev/null 2>&1 || true
    fi
  fi
  rm -f "$ALL_KEYS_FILE" "$LIVE_IDS_FILE"
  echo "Redis 反向孤儿已清理: $REDIS_ORPHAN 个"

  echo "剩余业务数据: errand=$($MYSQL -e 'SELECT COUNT(*) FROM errand') grab_record=$($MYSQL -e 'SELECT COUNT(*) FROM grab_record')"
  echo "seed 账户数（不应被清理）: $($MYSQL -e 'SELECT COUNT(*) FROM wallet_account')"
  echo "Redis 剩余 key 数: $(docker exec $REDIS_CONTAINER redis-cli DBSIZE)"
  exit 0
fi

# 组装待清理轮次的筛选条件
KIND_FILTER=""
case "$SCOPE" in
  it)    KIND_FILTER="AND kind = 'IT'" ;;
  bench) KIND_FILTER="AND kind = 'BENCH'" ;;
  all)   KIND_FILTER="" ;;
  *) echo "--scope 只支持 it|bench|all" >&2; exit 1 ;;
esac

if [ -n "$RUN_ID" ]; then
  TARGET_SQL="SELECT run_id FROM bench_run WHERE run_id = '$RUN_ID'"
elif [ -n "$BEFORE" ]; then
  TARGET_SQL="SELECT run_id FROM bench_run WHERE started_at < '$BEFORE 00:00:00' $KIND_FILTER"
elif [ "$CLEAN_ALL" = true ]; then
  if [ "$FORCE" != true ]; then
    echo "--all 会清掉全部轮次数据，请加 --force 确认" >&2; exit 1
  fi
  TARGET_SQL="SELECT run_id FROM bench_run WHERE 1=1 $KIND_FILTER"
else
  # 默认策略：保留最近 KEEP_LAST 轮，其余清理
  TARGET_SQL="SELECT run_id FROM bench_run WHERE 1=1 $KIND_FILTER
                AND run_id NOT IN (
                  SELECT run_id FROM (
                    SELECT run_id FROM bench_run WHERE 1=1 $KIND_FILTER
                     ORDER BY started_at DESC LIMIT $KEEP_LAST
                  ) keep
                )"
fi

TARGETS=$($MYSQL -e "$TARGET_SQL" | tr '\n' ' ' | xargs || true)
if [ -z "$TARGETS" ]; then
  echo "没有需要清理的轮次（scope=$SCOPE keep-last=$KEEP_LAST）"
  exit 0
fi

RUN_LIST=$(echo "$TARGETS" | tr ' ' '\n' | sed "s/^/'/;s/$/'/" | paste -sd, -)
echo "待清理轮次: $TARGETS"

# 先取出这些轮次关联的任务 id，用于 Redis 侧精确删 key
ERRAND_IDS=$($MYSQL -e "SELECT entity_id FROM bench_run_item
                         WHERE entity_type='ERRAND' AND run_id IN ($RUN_LIST)" | tr '\n' ' ' | xargs || true)
ERRAND_COUNT=$(echo "$ERRAND_IDS" | wc -w | xargs)
echo "关联任务数: $ERRAND_COUNT"

# Redis：按任务 id 精确删除三类 key，不再 FLUSHALL（FLUSHALL 会连 seed 与其他轮次一起毁掉）
if [ "$ERRAND_COUNT" -gt 0 ]; then
  # 批量删除：每个 id 一次 docker exec 的话，1 万条要跑 5 分钟（实测超时过）。
  # 把所有 key 拼成一条 DEL 命令通过 stdin 喂给 redis-cli，一次往返搞定。
  echo "$ERRAND_IDS" | tr ' ' '\n' | awk 'NF{printf "DEL errand:slot:{%s} errand:grabbed:{%s} errand:candidates:{%s}\n", $1, $1, $1}' \
    | docker exec -i "$REDIS_CONTAINER" redis-cli --pipe > /dev/null 2>&1 || true
  echo "Redis key 已按任务 id 批量删除（$ERRAND_COUNT 个任务）"
fi

# MySQL：按关联任务删除业务数据。删除顺序遵循外键依赖方向（本项目无外键，仍按逻辑顺序）
$MYSQL <<SQL
CREATE TEMPORARY TABLE tmp_ids (id BIGINT PRIMARY KEY);
INSERT IGNORE INTO tmp_ids
  SELECT entity_id FROM bench_run_item WHERE entity_type='ERRAND' AND run_id IN ($RUN_LIST);

DELETE g FROM grab_record g       JOIN tmp_ids t ON t.id = g.errand_id;
DELETE s FROM errand_status_log s JOIN tmp_ids t ON t.id = s.errand_id;
DELETE l FROM wallet_ledger l     JOIN tmp_ids t ON t.id = l.ref_id;
DELETE e FROM escrow_order e      JOIN tmp_ids t ON t.id = e.errand_id;
DELETE m FROM local_message m     JOIN tmp_ids t ON m.msg_key LIKE CONCAT('%:', t.id, ':%');
DELETE x FROM errand x            JOIN tmp_ids t ON t.id = x.id;

DELETE FROM bench_run_item WHERE run_id IN ($RUN_LIST);
DELETE FROM bench_run      WHERE run_id IN ($RUN_LIST);
SQL

# 冻结余额可能因清理任务而悬空，重置 seed 账户到基线状态
$MYSQL -e "UPDATE wallet_account SET available = 100000, frozen = 0 WHERE owner_id = 1001 AND owner_type='USER';
           UPDATE wallet_account SET available = 0, frozen = 0 WHERE owner_type IN ('ESCROW','COMMISSION');"

echo "清理完成。剩余轮次:"
$MYSQL -B -e "SELECT run_id, kind, scenario, status, started_at FROM bench_run ORDER BY started_at DESC LIMIT 15"
echo "剩余业务数据: errand=$($MYSQL -e 'SELECT COUNT(*) FROM errand') grab_record=$($MYSQL -e 'SELECT COUNT(*) FROM grab_record')"
echo "seed 账户数（不应被清理）: $($MYSQL -e 'SELECT COUNT(*) FROM wallet_account')"
echo "Redis 剩余 key 数: $(docker exec $REDIS_CONTAINER redis-cli DBSIZE)"
