
-- 优惠券库存：type 用整数枚举（见 VoucherType），第一档 100 张、逐档减半
CREATE TABLE IF NOT EXISTS voucher_stock (
  type             INT         NOT NULL COMMENT '优惠券类型，见 VoucherType（1..11）',
  stock            INT         NOT NULL COMMENT '剩余库存',
  description      VARCHAR(64) NOT NULL COMMENT '满减规则描述',
  last_change_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='优惠券库存';

-- 领取记录：voucher_id 是雪花 ID（高并发下应用侧生成，避免自增热点）
CREATE TABLE IF NOT EXISTS voucher_record (
  voucher_id   BIGINT      NOT NULL COMMENT '雪花 ID',
  user_id      VARCHAR(36) NOT NULL COMMENT '所属用户',
  voucher_type INT         NOT NULL COMMENT '优惠券类型',
  expire_time  DATETIME(3) NOT NULL COMMENT '过期时间',
  status       VARCHAR(16) NOT NULL COMMENT 'available / used / expired',
  created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (voucher_id),
  KEY idx_voucher_user (user_id, status),
  KEY idx_voucher_type (voucher_type),
  KEY idx_voucher_expire (expire_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='优惠券领取记录';

-- 种子库存：INSERT IGNORE 保证重复启动不会把已消耗的库存重置
INSERT IGNORE INTO voucher_stock (type, stock, description) VALUES
  (1, 100, '满10元减2元'),
  (2,  50, '满10元减3元'),
  (3,  25, '满10元减5元'),
  (4, 100, '满15元减2元'),
  (5,  50, '满15元减3元'),
  (6,  25, '满15元减5元'),
  (7,  12, '满15元减7元'),
  (8, 100, '满20元减5元'),
  (9,  50, '满20元减8元'),
  (10, 100, '满25元减10元'),
  (11,  50, '满25元减15元');
