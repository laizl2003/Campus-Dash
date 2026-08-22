-- 按轮次校验（取代 verify_s1.sql 的全表版本）
-- 用法：
--   RUN_ID=20260818-014200
--   docker exec -i dash-mysql mysql -uroot -pdash123456 -D campus_dash \
--     -e "SET @run_id='$RUN_ID';" --init-command="SET @run_id='$RUN_ID'" < verify_run.sql
-- 或直接：sed "s/__RUN_ID__/$RUN_ID/" verify_run.sql | docker exec -i dash-mysql mysql ...
--
-- 只看客户端报告是不够的：可能 DB 写了两条但其中一条响应超时，
-- 客户端视角只看到一个成功。所以必须回到数据库验证。

SET @run_id = IFNULL(@run_id, '__RUN_ID__');

SELECT CONCAT('===== 校验轮次 ', @run_id, ' =====') AS header;

SELECT '--- 校验1：抢中人数不得超过名额数（INV-1 超卖检测）---' AS check_item;
SELECT e.id AS errand_id, e.slot_total, e.slot_taken,
       COUNT(g.id) AS grabbed_rows,
       CASE WHEN COUNT(g.id) <= e.slot_total THEN 'PASS' ELSE 'FAIL-OVERSELL' END AS result
  FROM bench_run_item i
  JOIN errand e ON e.id = i.entity_id
  LEFT JOIN grab_record g ON g.errand_id = e.id AND g.result = 'GRABBED'
 WHERE i.run_id = @run_id AND i.entity_type = 'ERRAND'
 GROUP BY e.id, e.slot_total, e.slot_taken
HAVING result <> 'PASS'
    OR e.id = (SELECT MIN(entity_id) FROM bench_run_item WHERE run_id = @run_id AND entity_type='ERRAND');
-- 只输出违规行 + 一行样本，避免上千条任务把报告刷爆

SELECT '--- 校验1汇总 ---' AS check_item;
SELECT COUNT(*) AS errand_count,
       SUM(CASE WHEN e.slot_taken > e.slot_total THEN 1 ELSE 0 END) AS oversold_errands,
       CASE WHEN SUM(CASE WHEN e.slot_taken > e.slot_total THEN 1 ELSE 0 END) = 0
            THEN 'PASS' ELSE 'FAIL-OVERSELL' END AS result
  FROM bench_run_item i JOIN errand e ON e.id = i.entity_id
 WHERE i.run_id = @run_id AND i.entity_type = 'ERRAND';

SELECT '--- 校验2：同一用户不得占多个名额（INV-2），空集为 PASS ---' AS check_item;
SELECT g.errand_id, g.runner_id, COUNT(*) AS cnt
  FROM bench_run_item i JOIN grab_record g ON g.errand_id = i.entity_id
 WHERE i.run_id = @run_id AND i.entity_type = 'ERRAND' AND g.result = 'GRABBED'
 GROUP BY g.errand_id, g.runner_id
HAVING cnt > 1;

SELECT '--- 校验3：任务状态与抢中记录自洽 ---' AS check_item;
SELECT COUNT(*) AS total,
       SUM(CASE WHEN e.slot_taken > 0 AND e.grabber_id IS NULL THEN 1 ELSE 0 END) AS bad_rows,
       CASE WHEN SUM(CASE WHEN e.slot_taken > 0 AND e.grabber_id IS NULL THEN 1 ELSE 0 END) = 0
            THEN 'PASS' ELSE 'FAIL' END AS result
  FROM bench_run_item i JOIN errand e ON e.id = i.entity_id
 WHERE i.run_id = @run_id AND i.entity_type = 'ERRAND';

SELECT '--- 校验4：本轮资金借贷平衡（复式记账不变式）---' AS check_item;
SELECT COALESCE(SUM(CASE WHEN l.direction='DEBIT'  THEN l.amount ELSE 0 END), 0) AS total_debit,
       COALESCE(SUM(CASE WHEN l.direction='CREDIT' THEN l.amount ELSE 0 END), 0) AS total_credit,
       CASE WHEN COALESCE(SUM(CASE WHEN l.direction='DEBIT' THEN l.amount ELSE -l.amount END), 0) = 0
            THEN 'PASS' ELSE 'FAIL-UNBALANCED' END AS result
  FROM bench_run_item i JOIN wallet_ledger l ON l.ref_id = i.entity_id
 WHERE i.run_id = @run_id AND i.entity_type = 'ERRAND';

SELECT '--- 轮次摘要 ---' AS check_item;
SELECT run_id, kind, scenario, status, concurrency, started_at, finished_at, summary
  FROM bench_run WHERE run_id = @run_id;
