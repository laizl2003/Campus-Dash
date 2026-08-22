-- S4 结算压测造数
--
-- 关键：不能只造 errand。结算要读 escrow_order（HELD）与托管流水，
-- 少任何一样都会因 ESCROW_NOT_FOUND 或对账不平而全军覆没。
-- 所以这里一次造齐四样：errand(DELIVERED) + escrow_order(HELD)
--                      + wallet_ledger(托管借贷两条) + 托管账户余额
--
-- 用法：
--   S4_COUNT=500 S4_REWARD=2000 bash -c '...'  （由 SettleLoadClient 调用前先跑本脚本）
--   或直接： SET @cnt = 500; SOURCE seed_s4.sql;
--
-- 造数走 SQL 而不是接口：S4 要测的是"结算并发正确性"，不是造数速度。

SET @cnt    = IFNULL(@cnt, 500);
SET @reward = IFNULL(@reward, 2000);
SET @base   = 900000000000;   -- 固定 id 段，便于清理时精确定位
SET @runner = 2001;
SET @pub    = 1001;

-- 先清掉上一轮的 S4 数据（只动本 id 段，不碰其他轮次）
DELETE FROM wallet_ledger WHERE ref_id  >= @base AND ref_id  < @base + 1000000;
DELETE FROM escrow_order  WHERE errand_id >= @base AND errand_id < @base + 1000000;
DELETE FROM errand        WHERE id      >= @base AND id      < @base + 1000000;

-- 1) 任务：直接造成 DELIVERED，跳过前置流转（前置链路已由集成测试覆盖）
INSERT INTO errand (id, campus_id, publisher_id, grabber_id, type, title,
                    reward_amount, slot_total, slot_taken, status, round, version,
                    locked_at, delivered_at)
WITH RECURSIVE seq(n) AS (
  SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < @cnt
)
SELECT @base + n, 1, @pub, @runner, 'DELIVERY', CONCAT('bench_s4_', n),
       @reward, 1, 1, 'DELIVERED', 0, 3,
       DATE_SUB(NOW(3), INTERVAL 2 HOUR), DATE_SUB(NOW(3), INTERVAL 1 HOUR)
FROM seq;

-- 2) 托管单：全部 HELD
INSERT INTO escrow_order (id, campus_id, errand_id, publisher_id, amount, status)
WITH RECURSIVE seq(n) AS (
  SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < @cnt
)
SELECT @base + 100000 + n, 1, @base + n, @pub, @reward, 'HELD' FROM seq;

-- 3) 托管流水：一借一贷，让 L1 借贷平衡从造数起就成立
INSERT INTO wallet_ledger (id, biz_no, account_id, user_id, direction, amount,
                           balance_after, ref_type, ref_id)
WITH RECURSIVE seq(n) AS (
  SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < @cnt
)
SELECT @base + 200000 + n, CONCAT('escrow:', @base + n),
       (SELECT id FROM wallet_account WHERE owner_id = @pub AND owner_type = 'USER'),
       @pub, 'DEBIT', @reward, 0, 'ESCROW', @base + n FROM seq;

INSERT INTO wallet_ledger (id, biz_no, account_id, user_id, direction, amount,
                           balance_after, ref_type, ref_id)
WITH RECURSIVE seq(n) AS (
  SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < @cnt
)
SELECT @base + 300000 + n, CONCAT('escrow:', @base + n),
       (SELECT id FROM wallet_account WHERE owner_id = -1 AND owner_type = 'ESCROW'),
       @pub, 'CREDIT', @reward, 0, 'ESCROW', @base + n FROM seq;

-- 4) 账户快照与流水对齐：托管户 = 全部流水净额，让 L2 从造数起就成立
UPDATE wallet_account a
   SET a.available = IFNULL((SELECT SUM(CASE WHEN l.direction = 'CREDIT' THEN l.amount
                                             ELSE -l.amount END)
                               FROM wallet_ledger l WHERE l.account_id = a.id), 0)
 WHERE a.owner_type IN ('ESCROW', 'COMMISSION');

-- 发单人给足余额（避免负数，压测不关心发单人余额）
UPDATE wallet_account SET available = 100000000 WHERE owner_id = @pub AND owner_type = 'USER';
UPDATE wallet_account SET available = 0 WHERE owner_id = @runner AND owner_type = 'USER';

SELECT CONCAT('已造 ', @cnt, ' 个 DELIVERED 任务，id 段 [', @base + 1, ', ', @base + @cnt, ']') AS seeded;
