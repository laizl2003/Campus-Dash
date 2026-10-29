-- 历史聊天表：与 chat_message 结构/索引一致，归档时保留原始雪花 id
CREATE TABLE IF NOT EXISTS history_message LIKE chat_message;
