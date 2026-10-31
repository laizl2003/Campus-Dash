package com.campusdash.application.usecase.chat;

import com.campusdash.domain.chat.model.ChatMessage;
import com.campusdash.domain.chat.ports.ChatBroadcaster;
import com.campusdash.domain.auth.ports.UserRepository;
import com.campusdash.domain.chat.ports.ChatMessageRepository;
import com.campusdash.domain.errand.model.ErrandType;
import com.campusdash.shared.SnowflakeIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;

/** 发单后写一条 ERRAND 聊天卡片并广播；content 存任务卡片 JSON。 */
@Service
public class ChatErrandNoticeUseCase {

    private static final Logger log = LoggerFactory.getLogger(ChatErrandNoticeUseCase.class);

    private final ChatMessageRepository messages;
    private final ChatBroadcaster broadcaster;
    private final UserRepository users;
    private final SnowflakeIdGenerator idGenerator;

    public ChatErrandNoticeUseCase(ChatMessageRepository messages, ChatBroadcaster broadcaster,
                                   UserRepository users, SnowflakeIdGenerator idGenerator) {
        this.messages = messages;
        this.broadcaster = broadcaster;
        this.users = users;
        this.idGenerator = idGenerator;
    }

    public void published(long errandId, String publisherId, ErrandType type,
                          String title, String locationFrom, String locationTo,
                          Instant expiredAt, String description,
                          long rewardCents, long netRewardCents) {
        String publisherName = users.findById(publisherId)
                .map(user -> user.username())
                .orElse(publisherId);
        String content = String.format(
                "{\"type\":\"%s\",\"title\":\"%s\",\"locationFrom\":%s,\"locationTo\":%s,"
                        + "\"expiredAt\":%s,\"description\":%s,"
                        + "\"rewardCents\":%d,\"netRewardCents\":%d,\"publisherId\":\"%s\",\"publisherName\":\"%s\"}",
                type.name(), escape(title), jsonString(locationFrom), jsonString(locationTo),
                jsonString(expiredAt == null ? null : expiredAt.toString()),
                jsonString(description),
                rewardCents, netRewardCents, escape(publisherId), escape(publisherName));
        ChatMessage message = new ChatMessage(
                idGenerator.nextId(), SendChatMessageUseCase.ROOM, 1,
                publisherId, "发单人", "user", content, "ERRAND",
                errandId, "PUBLISHED", null, 1, Instant.now());
        messages.save(message);
        try {
            broadcaster.broadcast(message);
        } catch (RuntimeException e) {
            log.warn("发单聊天卡片广播失败 errandId={}", errandId, e);
        }
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 卡片里的可空字段输出 null，前端可据此判断"未填写" */
    private static String jsonString(String value) {
        return value == null ? "null" : "\"" + escape(value) + "\"";
    }
}
