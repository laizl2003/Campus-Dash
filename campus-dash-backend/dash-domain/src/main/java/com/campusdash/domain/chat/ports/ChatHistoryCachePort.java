package com.campusdash.domain.chat.ports;

import com.campusdash.domain.chat.model.ChatMessage;

import java.util.List;

/** 聊天最近消息热缓存（Redis ZSET，最多 200 条）。 */
public interface ChatHistoryCachePort {

    void push(ChatMessage message);

    /** 按 id 升序返回最近 limit 条；缓存不可用返回空列表 */
    List<ChatMessage> latest(String roomId, int limit);

    int size(String roomId);

    /** 只更新当前 errand 对应 ERRAND 卡片的 errandStatus，不动 ZSET */
    void updateErrandStatus(String roomId, long errandId, String status);

    /** 全量失效，仅缓存结构异常/兜底使用 */
    void invalidate(String roomId);
}
