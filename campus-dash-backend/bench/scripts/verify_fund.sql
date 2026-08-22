-- S4 资金专项校验：四条不变式，全部 PASS 才算通过
--
-- 与 verify_run.sql 的分工：verify_run 校验抢单侧（名额守恒、幂等），
-- 本脚本校验资金侧。两者都要过。

-- ① L1 借贷平衡：复式记账的根本不变式，全局必须为 0
SELECT '① L1 借贷平衡' AS 校验项,
       SUM(CASE WHEN direction = 'DEBIT'  THEN amount ELSE 0 END) AS 借方,
       SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE 0 END) AS 贷方,
       IF(SUM(CASE WHEN direction = 'DEBIT'  THEN amount ELSE -amount END) = 0,
          'PASS', 'FAIL') AS 结果
FROM wallet_ledger;

-- ② L2 系统户快照一致：available+frozen 必须等于流水净额
--    只校验系统户，用户账户的初始余额由 seed 直接给、无对应流水
SELECT '② L2 系统户快照' AS 校验项, a.owner_type AS 账户,
       a.available + a.frozen AS 快照,
       IFNULL((SELECT SUM(CASE WHEN l.direction = 'CREDIT' THEN l.amount ELSE -l.amount END)
                 FROM wallet_ledger l WHERE l.account_id = a.id), 0) AS 流水净额,
       IF(a.available + a.frozen =
          IFNULL((SELECT SUM(CASE WHEN l.direction = 'CREDIT' THEN l.amount ELSE -l.amount END)
                    FROM wallet_ledger l WHERE l.account_id = a.id), 0),
          'PASS', 'FAIL') AS 结果
FROM wallet_account a WHERE a.owner_type IN ('ESCROW', 'COMMISSION');

-- ③ L3 托管闭环：RELEASED 必须有结算流水，且任务与托管单状态不脱节
SELECT '③ L3 托管闭环' AS 校验项, COUNT(*) AS 异常条数,
       IF(COUNT(*) = 0, 'PASS', 'FAIL') AS 结果
FROM (
  SELECT e.errand_id FROM escrow_order e
   WHERE e.status = 'RELEASED'
     AND NOT EXISTS (SELECT 1 FROM wallet_ledger l WHERE l.biz_no = CONCAT('settle:', e.errand_id))
  UNION ALL
  SELECT e.errand_id FROM escrow_order e
   WHERE e.status = 'REFUNDED'
     AND NOT EXISTS (SELECT 1 FROM wallet_ledger l WHERE l.biz_no = CONCAT('refund:', e.errand_id))
  UNION ALL
  SELECT e.errand_id FROM escrow_order e JOIN errand r ON r.id = e.errand_id
   WHERE e.status = 'HELD' AND r.status IN ('SETTLED', 'REFUNDED', 'CANCELLED')
) t;

-- ④ 结算幂等：每个已结算任务的 settle 流水必须恰好是 2 条（托管借 + 跑腿贷）
--    或 3 条（含佣金贷）。多于 3 条说明重复结算打了多次钱。
SELECT '④ 结算幂等' AS 校验项, COUNT(*) AS 流水条数异常的任务数,
       IF(COUNT(*) = 0, 'PASS', 'FAIL') AS 结果
FROM (
  SELECT ref_id, COUNT(*) c FROM wallet_ledger
   WHERE ref_type = 'SETTLE' GROUP BY ref_id HAVING c NOT IN (2, 3)
) t;

-- ⑤ 结算金额守恒：跑腿所得 + 佣金 必须等于托管额，一分不差
SELECT '⑤ 结算金额守恒' AS 校验项, COUNT(*) AS 不守恒的任务数,
       IF(COUNT(*) = 0, 'PASS', 'FAIL') AS 结果
FROM (
  SELECT l.ref_id,
         SUM(CASE WHEN l.direction = 'DEBIT'  THEN l.amount ELSE 0 END) AS 转出,
         SUM(CASE WHEN l.direction = 'CREDIT' THEN l.amount ELSE 0 END) AS 转入
    FROM wallet_ledger l WHERE l.ref_type = 'SETTLE'
   GROUP BY l.ref_id HAVING 转出 <> 转入
) t;
