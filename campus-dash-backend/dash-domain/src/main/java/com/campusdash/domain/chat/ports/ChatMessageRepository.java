package com.campusdash.domain.chat.ports;

import com.campusdash.domain.chat.model.ChatMessage;

import java.util.List;
import java.util.Optional;

/** 聊天消息仓储。 */
public interface ChatMessageRepository {

    void save(ChatMessage message);

    Optional<ChatMessage> findBySenderAndClientId(String senderId, String clientMsgId);

    /** 最近 N 条，倒序查询，由调用方反转为升序 */
    List<ChatMessage> latest(String roomId, int limit);

    /** 向上翻：id 小于 beforeId，倒序 */
    List<ChatMessage> before(String roomId, long beforeId, int limit);

    /** 补洞：id 大于 afterId，升序 */
    List<ChatMessage> after(String roomId, long afterId, int limit);

    /** 历史表：最近 N 条，倒序 */
    List<ChatMessage> latestHistory(String roomId, int limit);

    /** 历史表：id 小于 beforeId，倒序 */
    List<ChatMessage> beforeHistory(String roomId, long beforeId, int limit);

    /** 历史表：id 大于 afterId，升序 */
    List<ChatMessage> afterHistory(String roomId, long afterId, int limit);
}
