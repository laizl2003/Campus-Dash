package com.campusdash.application.usecase.chat;

import com.campusdash.domain.chat.model.ChatMessage;
import com.campusdash.domain.chat.ports.ChatHistoryCachePort;
import com.campusdash.domain.chat.ports.ChatMessageRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 聊天历史：最近 N 条 / beforeId 上翻 / afterId 补洞，统一返回升序。 */
@Service
public class QueryChatHistoryUseCase {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 100;
    private static final int CACHE_WARM_SIZE = 10000;

    private final ChatMessageRepository messages;
    private final ChatHistoryCachePort cache;

    public QueryChatHistoryUseCase(ChatMessageRepository messages, ChatHistoryCachePort cache) {
        this.messages = messages;
        this.cache = cache;
    }

    public List<ChatMessage> history(String roomId, int limit, Long beforeId, Long afterId) {
        int size = Math.max(1, Math.min(limit <= 0 ? DEFAULT_LIMIT : limit, MAX_LIMIT));
        if (beforeId != null) {
            return beforeAcrossTables(roomId, beforeId, size);
        }
        if (afterId != null) {
            return afterAcrossTables(roomId, afterId, size);
        }

        // 首页只读最近 10000 条热缓存；缓存不足时回源实时表并回填
        List<ChatMessage> cached = cache.latest(roomId, size);
        if (cached.size() >= size) {
            return cached;
        }
        List<ChatMessage> live = messages.latest(roomId, CACHE_WARM_SIZE);
        for (ChatMessage message : live) {
            cache.push(message);
        }
        if (live.size() >= size) {
            return ascending(live, size, true);
        }
        // 实时表为空或不足：把历史表的最新消息补上
        List<ChatMessage> history = messages.latestHistory(roomId, size);
        return ascending(merge(live, history), size, true);
    }

    private List<ChatMessage> beforeAcrossTables(String roomId, long beforeId, int size) {
        List<ChatMessage> live = messages.before(roomId, beforeId, size);
        List<ChatMessage> history = messages.beforeHistory(roomId, beforeId, size);
        return ascending(merge(live, history), size, true);
    }

    private List<ChatMessage> afterAcrossTables(String roomId, long afterId, int size) {
        List<ChatMessage> live = messages.after(roomId, afterId, size);
        List<ChatMessage> history = messages.afterHistory(roomId, afterId, size);
        return ascending(merge(live, history), size, false);
    }

    private static List<ChatMessage> merge(List<ChatMessage> first, List<ChatMessage> second) {
        Map<Long, ChatMessage> map = new LinkedHashMap<>();
        first.forEach(m -> map.put(m.id(), m));
        second.forEach(m -> map.putIfAbsent(m.id(), m));
        return new ArrayList<>(map.values());
    }

    /**
     * 取排序后的最近 size 条。
     * fromNewest=true：输入/输出都按 id 升序，取末尾 size 条；
     * 为 false：同样最终返回升序，但代表 afterId 之后的“最早 size 条”。
     */
    private static List<ChatMessage> ascending(List<ChatMessage> rows, int size, boolean fromNewest) {
        List<ChatMessage> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparingLong(ChatMessage::id));
        if (sorted.size() <= size) return sorted;
        return fromNewest
                ? new ArrayList<>(sorted.subList(sorted.size() - size, sorted.size()))
                : new ArrayList<>(sorted.subList(0, size));
    }
}
