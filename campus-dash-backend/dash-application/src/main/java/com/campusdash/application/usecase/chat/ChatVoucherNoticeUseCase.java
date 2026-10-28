package com.campusdash.application.usecase.chat;

import com.campusdash.domain.chat.model.ChatMessage;
import com.campusdash.domain.chat.ports.ChatBroadcaster;
import com.campusdash.domain.chat.ports.ChatMessageRepository;
import com.campusdash.shared.SnowflakeIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;

/** Admin 发布优惠券后写 VOUCHER 聊天卡片并广播。 */
@Service
public class ChatVoucherNoticeUseCase {

    private static final Logger log = LoggerFactory.getLogger(ChatVoucherNoticeUseCase.class);

    private final ChatMessageRepository messages;
    private final ChatBroadcaster broadcaster;
    private final SnowflakeIdGenerator idGenerator;

    public ChatVoucherNoticeUseCase(ChatMessageRepository messages, ChatBroadcaster broadcaster,
                                    SnowflakeIdGenerator idGenerator) {
        this.messages = messages;
        this.broadcaster = broadcaster;
        this.idGenerator = idGenerator;
    }

    public void voucherCreated(String description, int stock) {
        String content = String.format(
                "{\"description\":\"%s\",\"stock\":%d}",
                escape(description), stock);
        ChatMessage message = new ChatMessage(
                idGenerator.nextId(), SendChatMessageUseCase.ROOM, 1,
                "system", "系统", "system", content, "VOUCHER",
                null, null, null, 1, Instant.now());
        messages.save(message);
        try {
            broadcaster.broadcast(message);
        } catch (RuntimeException e) {
            log.warn("优惠券聊天卡片广播失败", e);
        }
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
