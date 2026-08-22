-- S5 造数：N 条 LOCKED 任务，locked_at 全部设为同一时刻（已过期），模拟集中到期。
--
-- 为什么直接写 SQL 而不走接口：走接口造 1 万条要发 1 万次 HTTP + 1 万次抢单，
-- 光造数就要几分钟，而 S5 要测的是"到期后多快被流转"，不是造数速度。
-- 用法：sed "s/__RUN_ID__/$RUN_ID/;s/__COUNT__/10000/" seed_s5.sql | mysql ...
SET @run_id = '__RUN_ID__';
SET @count  = __COUNT__;
SET @base   = 900000000000000000;   -- 固定 ID 段，与雪花 ID 不冲突，便于识别与清理

INSERT INTO bench_run (run_id, kind, scenario, started_at, status, concurrency, env_note)
VALUES (@run_id, 'BENCH', 'S5', NOW(3), 'RUNNING', @count, '集中到期流转准时性，兜底扫描通道')
ON DUPLICATE KEY UPDATE started_at = NOW(3);

-- 递归 CTE 批量造任务：状态 LOCKED、locked_at 已过期，等待被流转
INSERT INTO errand (id, campus_id, publisher_id, grabber_id, type, title, reward_amount,
                    slot_total, slot_taken, status, round, version, locked_at)
WITH RECURSIVE seq(n) AS (
  SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < @count
)
SELECT @base + n, 1, 1001, 2001 + (n % 100), 'DELIVERY',
       CONCAT('bench_s5_', n), 100, 1, 1, 'LOCKED', 0, 2,
       DATE_SUB(NOW(3), INTERVAL 3600 SECOND)   -- 一小时前抢中，必然已超时
  FROM seq;

-- 登记到轮次，便于 verify/cleanup 精确定位
INSERT INTO bench_run_item (run_id, entity_type, entity_id)
WITH RECURSIVE seq(n) AS (
  SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < @count
)
SELECT @run_id, 'ERRAND', @base + n FROM seq;

SELECT CONCAT('S5 造数完成 run_id=', @run_id, ' 任务数=', @count) AS result;
