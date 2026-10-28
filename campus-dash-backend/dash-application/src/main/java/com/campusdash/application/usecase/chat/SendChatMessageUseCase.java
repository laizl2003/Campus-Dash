package com.campusdash.application.usecase.chat;

import com.campusdash.domain.auth.ports.UserRepository;
import com.campusdash.domain.chat.model.ChatMessage;
import com.campusdash.domain.chat.ports.ChatBroadcaster;
import com.campusdash.domain.chat.ports.ChatMessageRepository;
import com.campusdash.domain.chat.ports.ChatRateLimiter;
import com.campusdash.shared.BizException;
import com.campusdash.shared.ErrorCode;
import com.campusdash.shared.SnowflakeIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/** 发送广场消息：校验 → 幂等查重 → 限流 → 落库 → 广播。 */
@Service
public class SendChatMessageUseCase {

    private static final Logger log = LoggerFactory.getLogger(SendChatMessageUseCase.class);
    public static final String ROOM = "square";
    public static final int MAX_LENGTH = 500;

    private final ChatMessageRepository messages;
    private final ChatBroadcaster broadcaster;
    private final UserRepository users;
    private final ChatRateLimiter rateLimiter;
    private final SnowflakeIdGenerator idGenerator;

    public SendChatMessageUseCase(ChatMessageRepository messages, ChatBroadcaster broadcaster,
                                  UserRepository users, ChatRateLimiter rateLimiter,
                                  SnowflakeIdGenerator idGenerator) {
        this.messages = messages;
        this.broadcaster = broadcaster;
        this.users = users;
        this.rateLimiter = rateLimiter;
        this.idGenerator = idGenerator;
    }

    public ChatMessage send(String senderId, String clientMsgId, String rawContent) {
        String content = rawContent == null ? "" : rawContent.trim();
        if (content.isEmpty() || content.codePointCount(0, content.length()) > MAX_LENGTH) {
            throw new BizException(ErrorCode.CHAT_INVALID_CONTENT, "长度需为 1-" + MAX_LENGTH);
        }
        String key = (clientMsgId == null || clientMsgId.isBlank())
                ? UUID.randomUUID().toString() : clientMsgId.trim();
        if (key.length() > 64) {
            throw new BizException(ErrorCode.CHAT_INVALID_CONTENT, "clientMsgId 过长");
        }

        // 断线重发：同一 sender + clientMsgId 直接返回已落库消息，不重复广播
        var existing = messages.findBySenderAndClientId(senderId, key);
        if (existing.isPresent()) {
            return existing.get();
        }
        if (!rateLimiter.allow(senderId)) {
            throw new BizException(ErrorCode.CHAT_RATE_LIMITED, "请稍后再试");
        }

        var user = users.findById(senderId)
                .orElseThrow(() -> new BizException(ErrorCode.UNAUTHORIZED, "sender=" + senderId));
        if (!users.messageAllowed(senderId)) {
            throw new BizException(ErrorCode.CHAT_MUTED, "sender=" + senderId);
        }
        ChatMessage message = new ChatMessage(
                idGenerator.nextId(), ROOM, user.campusId(), senderId,
                user.username(), user.role(), content, "TEXT", key, 1, Instant.now());
        messages.save(message);

        // 实时广播失败不回滚：DB 是事实源，客户端靠 history/afterId 补洞
        try {
            broadcaster.broadcast(message);
        } catch (RuntimeException e) {
            log.warn("聊天广播失败 messageId={}", message.id(), e);
        }
        return message;
    }
}
