-- P6 增量迁移：给任务共片表补分片键。
-- 用法：
--   docker exec -i campus-dash-mysql mysql -uroot -pdash123456 campus_dash < docker/migrate-p6.sql

SET NAMES utf8mb4;

SET @has_campus_id := (
  SELECT COUNT(*)
    FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE()
     AND TABLE_NAME = 'grab_record'
     AND COLUMN_NAME = 'campus_id'
);

SET @ddl := IF(@has_campus_id = 0,
  'ALTER TABLE grab_record ADD COLUMN campus_id BIGINT NULL COMMENT ''校区 ID，P6 与 errand 绑定分片'' AFTER id',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 托管单与任务共片，避免结算链路按 errand_id 查询时广播。
SET @has_escrow_campus_id := (
  SELECT COUNT(*)
    FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE()
     AND TABLE_NAME = 'escrow_order'
     AND COLUMN_NAME = 'campus_id'
);

SET @ddl := IF(@has_escrow_campus_id = 0,
  'ALTER TABLE escrow_order ADD COLUMN campus_id BIGINT NULL COMMENT ''校区 ID，P6 与 errand 绑定分片'' AFTER id',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE escrow_order eo
  JOIN errand e ON e.id = eo.errand_id
   SET eo.campus_id = e.campus_id
 WHERE eo.campus_id IS NULL;

-- 历史测试数据可能有孤儿托管单；迁移要保证可重复执行，不因脏历史中断。
UPDATE escrow_order
   SET campus_id = 1
 WHERE campus_id IS NULL;

ALTER TABLE escrow_order MODIFY campus_id BIGINT NOT NULL COMMENT '校区 ID，P6 与 errand 绑定分片';

SET @has_escrow_idx := (
  SELECT COUNT(*)
    FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE()
     AND TABLE_NAME = 'escrow_order'
     AND INDEX_NAME = 'idx_campus_errand'
);

SET @ddl := IF(@has_escrow_idx = 0,
  'ALTER TABLE escrow_order ADD INDEX idx_campus_errand (campus_id, errand_id)',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 状态日志与任务共片，后续状态时间线可按 campus_id + errand_id 精确路由。
SET @has_log_campus_id := (
  SELECT COUNT(*)
    FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE()
     AND TABLE_NAME = 'errand_status_log'
     AND COLUMN_NAME = 'campus_id'
);

SET @ddl := IF(@has_log_campus_id = 0,
  'ALTER TABLE errand_status_log ADD COLUMN campus_id BIGINT NULL COMMENT ''校区 ID，P6 与 errand 绑定分片'' AFTER id',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE errand_status_log l
  JOIN errand e ON e.id = l.errand_id
   SET l.campus_id = e.campus_id
 WHERE l.campus_id IS NULL;

-- 历史测试数据可能先清了 errand，留下孤儿状态日志；统一归到默认校区。
UPDATE errand_status_log
   SET campus_id = 1
 WHERE campus_id IS NULL;

ALTER TABLE errand_status_log MODIFY campus_id BIGINT NOT NULL COMMENT '校区 ID，P6 与 errand 绑定分片';

SET @has_log_idx := (
  SELECT COUNT(*)
    FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE()
     AND TABLE_NAME = 'errand_status_log'
     AND INDEX_NAME = 'idx_campus_errand_log'
);

SET @ddl := IF(@has_log_idx = 0,
  'ALTER TABLE errand_status_log ADD INDEX idx_campus_errand_log (campus_id, errand_id, id)',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE grab_record gr
  JOIN errand e ON e.id = gr.errand_id
   SET gr.campus_id = e.campus_id
 WHERE gr.campus_id IS NULL;

-- 历史测试数据可能有孤儿抢单记录；统一归到默认校区，避免 NOT NULL 迁移失败。
UPDATE grab_record
   SET campus_id = 1
 WHERE campus_id IS NULL;

ALTER TABLE grab_record MODIFY campus_id BIGINT NOT NULL COMMENT '校区 ID，P6 与 errand 绑定分片';

SET @has_idx := (
  SELECT COUNT(*)
    FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE()
     AND TABLE_NAME = 'grab_record'
     AND INDEX_NAME = 'idx_campus_errand'
);

SET @ddl := IF(@has_idx = 0,
  'ALTER TABLE grab_record ADD INDEX idx_campus_errand (campus_id, errand_id)',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
