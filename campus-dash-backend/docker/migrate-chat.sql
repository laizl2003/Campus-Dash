-- 广场聊天消息：append-only，id 既主键又游标。
-- sender_name / sender_role 存发送时快照：改名或角色变化不改历史。
-- 不建 chat_room / chat_user：一期只有 square，身份仍从 user 表读取。
CREATE TABLE IF NOT EXISTS chat_message (
  id            BIGINT       NOT NULL COMMENT '雪花 ID，时间有序，兼作游标',
  room_id       VARCHAR(32)  NOT NULL DEFAULT 'square' COMMENT '房间，一期固定 square',
  campus_id     BIGINT       NOT NULL DEFAULT 1 COMMENT '校区，分片键预留',
  sender_id     VARCHAR(36)  NOT NULL COMMENT '发送人 user_id（UUID / 001）',
  sender_name   VARCHAR(32)  NOT NULL COMMENT '发送时用户名快照',
  sender_role   VARCHAR(16)  NOT NULL DEFAULT 'user' COMMENT '发送时角色：user / Admin',
  content       VARCHAR(500) NOT NULL COMMENT '纯文本，最多 500 码点',
  msg_type      VARCHAR(16)  NOT NULL DEFAULT 'TEXT' COMMENT 'TEXT，预留 IMAGE/SYSTEM',
  client_msg_id VARCHAR(64)  NULL COMMENT '客户端幂等键',
  status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1 正常 / 0 撤回 / 2 屏蔽',
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_chat_client (sender_id, client_msg_id),
  KEY idx_chat_room (room_id, id),
  KEY idx_chat_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='广场聊天消息';
