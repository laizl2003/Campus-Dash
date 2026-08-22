-- 基线数据集（幂等，可反复执行）
-- 与压测产生的临时数据分开：cleanup.sh 只按 bench_run_item 删除轮次数据，绝不动这里的 seed。
SET NAMES utf8mb4;

-- 系统户
INSERT INTO wallet_account (id, owner_id, owner_type, available, frozen, version) VALUES
  (1, -1, 'ESCROW', 0, 0, 0),
  (2, -2, 'COMMISSION', 0, 0, 0)
ON DUPLICATE KEY UPDATE available = VALUES(available), frozen = VALUES(frozen);

-- 发单人：1001，余额 1000 元
INSERT INTO wallet_account (id, owner_id, owner_type, available, frozen, version) VALUES
  (1001, 1001, 'USER', 100000, 0, 0)
ON DUPLICATE KEY UPDATE available = 100000, frozen = 0;

-- 跑腿账户 2001-2100（S5 流转压测需要多个候选人）
-- 用递归 CTE 批量生成，MySQL 8 支持
INSERT INTO wallet_account (id, owner_id, owner_type, available, frozen, version)
WITH RECURSIVE seq(n) AS (
  SELECT 2001 UNION ALL SELECT n + 1 FROM seq WHERE n < 2100
)
SELECT n, n, 'USER', 5000, 0, 0 FROM seq
ON DUPLICATE KEY UPDATE available = 5000, frozen = 0;

SELECT CONCAT('seed 完成，账户数=', COUNT(*)) AS result FROM wallet_account;
