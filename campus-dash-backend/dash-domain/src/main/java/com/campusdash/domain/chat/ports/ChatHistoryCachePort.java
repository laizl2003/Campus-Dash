package com.campusdash.domain.chat.ports;

import com.campusdash.domain.chat.model.ChatMessage;

import java.util.List;

/** 聊天最近消息热缓存（Redis ZSET，最多 200 条）。 */
public interface ChatHistoryCachePort {

    void push(ChatMessage message);

    /** 按 id 升序返回最近 limit 条；缓存不可用返回空列表 */
    List<ChatMessage> latest(String roomId, int limit);

    int size(String roomId);

    /** 任务状态等变更后使最近消息缓存失效，下一次从 DB 重建 */
    void invalidate(String roomId);
}
