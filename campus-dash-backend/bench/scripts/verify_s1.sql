-- [已被 verify_run.sql 取代] 本文件是 P1 的全表版本校验，保留仅作历史参考。
-- 全表版本会把所有历史轮次的任务混在一起输出（P1 时一次列出 31 行），轮次累积后不可读。
-- 请使用 verify_run.sql 按 run_id 校验，避免两套校验口径并存。

-- S1 尖峰抢单的四条校验 SQL
-- 只看 JMeter/客户端报告是不够的：可能 DB 写了两条但其中一条响应超时，
-- 客户端视角看只有一个成功。必须回到数据库验证。
-- 用法：docker exec -i dash-mysql mysql -uroot -pdash123456 -D campus_dash < verify_s1.sql

SELECT '--- 校验1：抢中人数必须等于名额数（INV-1 超卖检测）---' AS check_item;
SELECT e.id AS errand_id, e.slot_total, e.slot_taken,
       COUNT(g.id) AS grabbed_rows,
       CASE WHEN COUNT(g.id) <= e.slot_total THEN 'PASS' ELSE 'FAIL-OVERSELL' END AS result
  FROM errand e
  LEFT JOIN grab_record g ON g.errand_id = e.id AND g.result = 'GRABBED'
 GROUP BY e.id, e.slot_total, e.slot_taken;

SELECT '--- 校验2：不能有同一用户占多个名额（INV-2）---' AS check_item;
SELECT errand_id, runner_id, COUNT(*) AS cnt
  FROM grab_record
 WHERE result = 'GRABBED'
 GROUP BY errand_id, runner_id
HAVING cnt > 1;
-- 返回空集才算 PASS

SELECT '--- 校验3：任务状态与抢中记录自洽 ---' AS check_item;
SELECT id, status, slot_total, slot_taken, grabber_id, version,
       CASE WHEN slot_taken <= slot_total
             AND (slot_taken = 0 OR grabber_id IS NOT NULL)
            THEN 'PASS' ELSE 'FAIL' END AS result
  FROM errand;

SELECT '--- 校验4：资金借贷平衡（复式记账不变式）---' AS check_item;
SELECT COALESCE(SUM(CASE WHEN direction='DEBIT'  THEN amount ELSE 0 END), 0) AS total_debit,
       COALESCE(SUM(CASE WHEN direction='CREDIT' THEN amount ELSE 0 END), 0) AS total_credit,
       CASE WHEN COALESCE(SUM(CASE WHEN direction='DEBIT' THEN amount ELSE -amount END), 0) = 0
            THEN 'PASS' ELSE 'FAIL-UNBALANCED' END AS result
  FROM wallet_ledger;
