package com.campusdash.application.usecase.chat;

import com.campusdash.domain.chat.model.ChatMessage;
import com.campusdash.domain.chat.ports.ChatHistoryCachePort;
import com.campusdash.domain.chat.ports.ChatMessageRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 聊天历史：最近 N 条 / beforeId 上翻 / afterId 补洞，统一返回升序。 */
@Service
public class QueryChatHistoryUseCase {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 100;
    private static final int CACHE_WARM_SIZE = 200;

    private final ChatMessageRepository messages;
    private final ChatHistoryCachePort cache;

    public QueryChatHistoryUseCase(ChatMessageRepository messages, ChatHistoryCachePort cache) {
        this.messages = messages;
        this.cache = cache;
    }

    public List<ChatMessage> history(String roomId, int limit, Long beforeId, Long afterId) {
        int size = Math.max(1, Math.min(limit <= 0 ? DEFAULT_LIMIT : limit, MAX_LIMIT));
        if (beforeId != null) {
            return reverse(messages.before(roomId, beforeId, size));
        }
        if (afterId != null) {
            return messages.after(roomId, afterId, size);
        }

        // 首页只读最近 200 条热缓存；缓存不足时回源 DB 并回填 200 条
        List<ChatMessage> cached = cache.latest(roomId, size);
        if (cached.size() >= size) {
            return cached;
        }
        List<ChatMessage> latest = reverse(messages.latest(roomId, CACHE_WARM_SIZE));
        for (ChatMessage message : latest) {
            cache.push(message);
        }
        if (latest.size() <= size) return latest;
        return new ArrayList<>(latest.subList(latest.size() - size, latest.size()));
    }

    private List<ChatMessage> reverse(List<ChatMessage> desc) {
        List<ChatMessage> copy = new ArrayList<>(desc);
        Collections.reverse(copy);
        return copy;
    }
}
