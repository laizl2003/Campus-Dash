-- 只读验收查询，仅执行 SELECT；请在业务请求结束、消费者稳定后运行。
-- 账户总额需要与本次独立环境建数后的基线比较，不能凭空假设初始总额。

-- 1. 所有账户资金总额应保持建数基线；用户冻结额不会因跑腿托管而增加。
SELECT COUNT(*) AS account_count,
       SUM(available) AS available_total_cents,
       SUM(frozen) AS frozen_total_cents,
       SUM(available + frozen) AS total_cents
FROM wallet_account;

-- 2. 每笔复式记账借贷平衡，异常笔数应为 0。
SELECT COUNT(*) AS unbalanced_businesses
FROM (
    SELECT biz_no
    FROM wallet_ledger
    GROUP BY biz_no
    HAVING SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END) <> 0
) AS violations;

-- 3. 系统账户快照等于流水净额，异常行数应为 0（系统账户初始余额为 0）。
SELECT a.id, a.owner_type, a.available + a.frozen AS snapshot_cents,
       COALESCE(l.net_cents, 0) AS ledger_net_cents
FROM wallet_account a
LEFT JOIN (
    SELECT account_id,
           SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE -amount END) AS net_cents
    FROM wallet_ledger
    GROUP BY account_id
) l ON l.account_id = a.id
WHERE a.owner_type IN ('ESCROW', 'COMMISSION')
  AND a.available + a.frozen <> COALESCE(l.net_cents, 0);

-- 4. 托管系统账户余额等于所有 HELD 托管单金额，difference_cents 应为 0。
SELECT a.escrow_balance_cents, e.held_total_cents,
       a.escrow_balance_cents - e.held_total_cents AS difference_cents
FROM (SELECT COALESCE(SUM(available + frozen), 0) AS escrow_balance_cents
      FROM wallet_account WHERE owner_type = 'ESCROW') a
CROSS JOIN (SELECT COALESCE(SUM(amount), 0) AS held_total_cents
            FROM escrow_order WHERE status = 'HELD') e;

-- 5. 业务终态与托管状态同步；异常行数应为 0。
SELECT e.errand_id, r.status AS errand_status, e.status AS escrow_status
FROM escrow_order e
LEFT JOIN errand r ON r.id = e.errand_id
WHERE r.id IS NULL
   OR (r.status = 'SETTLED' AND e.status <> 'RELEASED')
   OR (r.status IN ('CANCELLED', 'REFUNDED') AND e.status <> 'REFUNDED')
   OR (r.status IN ('PUBLISHED', 'LOCKED', 'ACCEPTED', 'PICKED_UP', 'DELIVERED', 'DISPUTED')
       AND e.status <> 'HELD');

-- 6. 每个已结算/退款托管单都有匹配流水，且不会既结算又退款；异常行数应为 0。
SELECT e.errand_id, e.status
FROM escrow_order e
WHERE (e.status = 'RELEASED' AND NOT EXISTS (
          SELECT 1 FROM wallet_ledger l WHERE l.biz_no = CONCAT('settle:', e.errand_id)))
   OR (e.status = 'REFUNDED' AND NOT EXISTS (
          SELECT 1 FROM wallet_ledger l WHERE l.biz_no = CONCAT('refund:', e.errand_id)))
   OR (EXISTS (SELECT 1 FROM wallet_ledger l WHERE l.biz_no = CONCAT('settle:', e.errand_id))
       AND EXISTS (SELECT 1 FROM wallet_ledger l WHERE l.biz_no = CONCAT('refund:', e.errand_id)));

-- 7. 已结算款项只发给当前跑腿与平台；借记/贷记总额等于该托管金额。
-- 每条 RELEASED 托管单应只存在一条托管借记，异常行数应为 0。
SELECT e.errand_id, e.amount,
       SUM(CASE WHEN l.direction = 'DEBIT' THEN l.amount ELSE 0 END) AS debit_cents,
       SUM(CASE WHEN l.direction = 'CREDIT' THEN l.amount ELSE 0 END) AS credit_cents
FROM escrow_order e
JOIN errand r ON r.id = e.errand_id
LEFT JOIN wallet_ledger l ON l.biz_no = CONCAT('settle:', e.errand_id)
LEFT JOIN wallet_account a ON a.id = l.account_id
WHERE e.status = 'RELEASED'
GROUP BY e.errand_id, e.amount
HAVING debit_cents <> e.amount OR credit_cents <> e.amount
    OR SUM(CASE WHEN l.direction = 'DEBIT' THEN 1 ELSE 0 END) <> 1
    OR SUM(CASE WHEN l.direction = 'DEBIT' AND a.owner_type <> 'ESCROW' THEN 1 ELSE 0 END) > 0
    OR SUM(CASE WHEN l.direction = 'CREDIT'
                     AND NOT (a.owner_type = 'COMMISSION'
                              OR (a.owner_type = 'USER' AND a.owner_id = r.grabber_id))
                THEN 1 ELSE 0 END) > 0;

-- 8. 每个账户的“快照减流水净额”应等于建数时该账户余额；结果可与基线逐户比较。
SELECT a.id, a.owner_id, a.owner_type,
       a.available + a.frozen AS current_cents,
       COALESCE(l.net_cents, 0) AS ledger_net_cents,
       a.available + a.frozen - COALESCE(l.net_cents, 0) AS inferred_initial_cents
FROM wallet_account a
LEFT JOIN (
    SELECT account_id,
           SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE -amount END) AS net_cents
    FROM wallet_ledger GROUP BY account_id
) l ON l.account_id = a.id
ORDER BY a.id;
