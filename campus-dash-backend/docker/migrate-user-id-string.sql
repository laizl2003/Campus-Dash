-- 用户身份从 BIGINT 迁移到 VARCHAR(36)：注册用户是 UUID，仲裁账号是 001。
-- 幂等：MODIFY 成同类型可重复执行；MySQL 会把已有数字值转成字符串（-1 -> '-1'）。
ALTER TABLE errand            MODIFY COLUMN publisher_id VARCHAR(36) NOT NULL COMMENT '发单人 user_id（UUID/001/1001）';
ALTER TABLE errand            MODIFY COLUMN grabber_id   VARCHAR(36) NULL     COMMENT '当前抢中者 user_id';
ALTER TABLE errand_status_log MODIFY COLUMN operator_id  VARCHAR(36) NOT NULL COMMENT '操作者 user_id，-1 表示系统';
ALTER TABLE escrow_order      MODIFY COLUMN publisher_id VARCHAR(36) NOT NULL COMMENT '发单人 user_id';
ALTER TABLE grab_record       MODIFY COLUMN runner_id    VARCHAR(36) NOT NULL COMMENT '跑腿 user_id';
ALTER TABLE wallet_account    MODIFY COLUMN owner_id     VARCHAR(36) NOT NULL COMMENT '账户所有者 user_id，-1/-2 为系统账户';
ALTER TABLE wallet_ledger     MODIFY COLUMN user_id      VARCHAR(36) NOT NULL COMMENT '流水归属 user_id';
ALTER TABLE credit_score      MODIFY COLUMN user_id      VARCHAR(36) NOT NULL COMMENT '信用分归属 user_id';
ALTER TABLE credit_event      MODIFY COLUMN user_id      VARCHAR(36) NOT NULL COMMENT '信用事件归属 user_id';
ALTER TABLE notification      MODIFY COLUMN user_id      VARCHAR(36) NOT NULL COMMENT '接收人 user_id';
ALTER TABLE fund_audit_log    MODIFY COLUMN operator_id  VARCHAR(36) NOT NULL COMMENT '操作者 user_id';
