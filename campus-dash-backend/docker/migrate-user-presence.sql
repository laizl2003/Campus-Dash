ALTER TABLE user
  ADD COLUMN login_status TINYINT NOT NULL DEFAULT 0 COMMENT '1=已登录/在线，0=离线',
  ADD COLUMN last_seen_at DATETIME(3) NULL COMMENT '最近心跳时间';

UPDATE user SET login_status = 0, last_seen_at = NULL;
