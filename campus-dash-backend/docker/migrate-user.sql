-- 账号表迁移脚本（已有库执行；全新库由 init.sql 建）。
-- 幂等：CREATE TABLE IF NOT EXISTS，可重复执行。
CREATE TABLE IF NOT EXISTS `user` (
  user_id             VARCHAR(36)  NOT NULL COMMENT '账号主键：注册用户为 UUID，Admin 固定 001',
  username            VARCHAR(32)  NOT NULL COMMENT '登录名',
  phone               VARCHAR(20)  NOT NULL COMMENT '手机号',
  password_hash       VARCHAR(160) NOT NULL COMMENT 'PBKDF2-SHA256，格式见 Pbkdf2PasswordHasher',
  password_updated_at DATETIME(3)  NULL COMMENT '密码修改时间',
  created_at          DATETIME(3)  NOT NULL COMMENT '账号创建时间',
  updated_at          DATETIME(3)  NOT NULL COMMENT '资料更新时间',
  role                VARCHAR(16)  NOT NULL DEFAULT 'user' COMMENT '身份：user / Admin',
  avatar_url          VARCHAR(255) NOT NULL DEFAULT '' COMMENT '头像地址，空表示用首字母占位',
  campus_id           BIGINT       NOT NULL DEFAULT 1 COMMENT '所属校区（分片键预留）',
  credit_score        INT          NOT NULL DEFAULT 60 COMMENT '冗余：登录页/列表直接显示，免回表 credit_score',
  status              TINYINT      NOT NULL DEFAULT 1 COMMENT '1 正常 / 0 禁用',
  last_login_at       DATETIME(3)  NULL COMMENT '冗余：最近登录时间，安全审计用',
  last_login_ip       VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '冗余：最近登录 IP',
  login_fail_count    INT          NOT NULL DEFAULT 0 COMMENT '连续登录失败次数，防爆破',
  remark              VARCHAR(255) NOT NULL DEFAULT '' COMMENT '运营备注',
  PRIMARY KEY (user_id),
  UNIQUE KEY uk_user_username (username),
  UNIQUE KEY uk_user_phone (phone),
  KEY idx_user_role (role),
  KEY idx_user_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账号';
