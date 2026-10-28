-- 聊天禁言：1 允许发言，0 禁止发言；老账号默认 1
ALTER TABLE `user`
  ADD COLUMN message_status TINYINT NOT NULL DEFAULT 1 COMMENT '1 允许发言 / 0 禁言'
  AFTER status;
