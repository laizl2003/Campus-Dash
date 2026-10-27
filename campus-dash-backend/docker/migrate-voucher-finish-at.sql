ALTER TABLE voucher_record
  ADD COLUMN finish_at DATETIME(3) NULL COMMENT '券结束时间：发布锁定时=使用时间，过期时=过期时间' AFTER status;

UPDATE voucher_record SET finish_at = NOW(3)
 WHERE status IN ('used','expired') AND finish_at IS NULL;
