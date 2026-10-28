-- ERRAND 消息卡片：关联任务与任务状态
ALTER TABLE chat_message
  ADD COLUMN errand_id BIGINT NULL COMMENT 'ERRAND 消息关联的任务 id' AFTER msg_type,
  ADD COLUMN errand_status VARCHAR(16) NULL COMMENT 'ERRAND 卡片当前任务状态' AFTER errand_id,
  ADD KEY idx_chat_errand (errand_id);
