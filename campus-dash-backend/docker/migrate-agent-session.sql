-- Agent 会话持久化：容器重启后历史对话仍可继续
CREATE TABLE IF NOT EXISTS agent_session (
  session_id   VARCHAR(64)  NOT NULL COMMENT '会话 ID（UUID）',
  user_id      VARCHAR(36)  NULL     COMMENT '所属用户，便于按人隔离',
  draft_json   JSON         NOT NULL COMMENT '当前任务草稿 TaskDraft',
  turns        INT          NOT NULL DEFAULT 0 COMMENT '对话轮数',
  status       VARCHAR(16)  NULL     COMMENT '最近一次状态 ASKING/READY',
  last_message VARCHAR(500) NULL     COMMENT '最近一条用户消息，历史列表展示用',
  created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (session_id),
  KEY idx_user_updated (user_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 对话会话';

-- 对话消息：复盘和前端恢复历史用
CREATE TABLE IF NOT EXISTS agent_message (
  id         BIGINT      NOT NULL AUTO_INCREMENT,
  session_id VARCHAR(64) NOT NULL COMMENT '所属会话',
  role       VARCHAR(16) NOT NULL COMMENT 'user / assistant',
  content    TEXT        NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_session (session_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 对话消息';
