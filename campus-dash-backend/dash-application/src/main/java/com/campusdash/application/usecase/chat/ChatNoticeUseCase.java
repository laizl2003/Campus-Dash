package com.campusdash.application.usecase.chat;

import com.campusdash.domain.chat.model.ChatMessage;
import com.campusdash.domain.chat.ports.ChatBroadcaster;
import com.campusdash.domain.chat.ports.ChatMessageRepository;
import com.campusdash.shared.SnowflakeIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;

/** 聊天系统通知：禁言/解禁后落一条 SYSTEM 消息并广播。 */
@Service
public class ChatNoticeUseCase {

    private static final Logger log = LoggerFactory.getLogger(ChatNoticeUseCase.class);

    private final ChatMessageRepository messages;
    private final ChatBroadcaster broadcaster;
    private final SnowflakeIdGenerator idGenerator;

    public ChatNoticeUseCase(ChatMessageRepository messages, ChatBroadcaster broadcaster,
                             SnowflakeIdGenerator idGenerator) {
        this.messages = messages;
        this.broadcaster = broadcaster;
        this.idGenerator = idGenerator;
    }

    public void messageStatusChanged(String username, boolean muted) {
        String content = username + (muted ? "已被禁言" : "已解除禁言");
        ChatMessage message = new ChatMessage(
                idGenerator.nextId(), SendChatMessageUseCase.ROOM, 1,
                "system", "系统", "system", content, "SYSTEM", null, null, null, 1, Instant.now());
        messages.save(message);
        try {
            broadcaster.broadcast(message);
        } catch (RuntimeException e) {
            log.warn("禁言通知广播失败 id={}", message.id(), e);
        }
    }
}
