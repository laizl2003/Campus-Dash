package com.campusdash.application.usecase.chat;

import com.campusdash.domain.chat.model.ChatMessage;
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

    private final ChatMessageRepository messages;

    public QueryChatHistoryUseCase(ChatMessageRepository messages) {
        this.messages = messages;
    }

    public List<ChatMessage> history(String roomId, int limit, Long beforeId, Long afterId) {
        int size = Math.max(1, Math.min(limit <= 0 ? DEFAULT_LIMIT : limit, MAX_LIMIT));
        if (beforeId != null) {
            return reverse(messages.before(roomId, beforeId, size));
        }
        if (afterId != null) {
            return messages.after(roomId, afterId, size);
        }
        return reverse(messages.latest(roomId, size));
    }

    private List<ChatMessage> reverse(List<ChatMessage> desc) {
        List<ChatMessage> copy = new ArrayList<>(desc);
        Collections.reverse(copy);
        return copy;
    }
}
