DROP TABLE IF EXISTS cleanup_ids;
CREATE TABLE cleanup_ids AS
SELECT e.id AS id, e.publisher_id AS publisher_id
  FROM errand e
 WHERE e.status IN ('DRAFT','PUBLISHED','LOCKED','ACCEPTED','PICKED_UP','DELIVERED','DISPUTED')
    OR e.title LIKE 'settle%' OR e.title LIKE 'refund%' OR e.title LIKE 'api_it%'
    OR e.title LIKE 'credit_%' OR e.title LIKE 'bench%'
    OR e.title LIKE '%测试%' OR e.title LIKE '%联调%' OR e.title LIKE '%验证%'
    OR e.title LIKE '%演示%' OR e.title = '历史测试任务';

ALTER TABLE cleanup_ids ADD PRIMARY KEY (id);

UPDATE wallet_account wa
  JOIN (
    SELECT c.publisher_id AS publisher_id, SUM(COALESCE(eo.amount, 0)) AS amt
      FROM cleanup_ids c
      LEFT JOIN escrow_order eo ON eo.errand_id = c.id AND eo.status = 'HELD'
     GROUP BY c.publisher_id
  ) x ON wa.owner_id = x.publisher_id AND wa.owner_type = 'USER'
   SET wa.available = wa.available + x.amt
 WHERE x.amt > 0;

DELETE FROM wallet_ledger WHERE biz_no IN (SELECT CONCAT('escrow:', id) FROM cleanup_ids);
DELETE FROM escrow_order WHERE errand_id IN (SELECT id FROM cleanup_ids);
DELETE FROM grab_record WHERE errand_id IN (SELECT id FROM cleanup_ids);
DELETE FROM errand_status_log WHERE errand_id IN (SELECT id FROM cleanup_ids);
DELETE FROM credit_event WHERE ref_type = 'ERRAND' AND ref_id IN (SELECT id FROM cleanup_ids);
DELETE FROM notification WHERE errand_id IN (SELECT id FROM cleanup_ids);
DELETE FROM errand WHERE id IN (SELECT id FROM cleanup_ids);

DROP TABLE cleanup_ids;
