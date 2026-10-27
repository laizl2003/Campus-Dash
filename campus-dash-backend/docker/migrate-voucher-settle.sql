-- 优惠券接入结算：锁定到订单、记录跑腿承担金额
ALTER TABLE voucher_record
  ADD COLUMN used_errand_id BIGINT NULL COMMENT '锁定/使用的订单' AFTER status,
  ADD COLUMN runner_bear_cents BIGINT NOT NULL DEFAULT 0 COMMENT '跑腿承担的券面金额' AFTER used_errand_id;
ALTER TABLE voucher_record
  ADD KEY idx_voucher_errand (used_errand_id);
