package com.campusdash.domain.chat.model;

import java.time.Instant;

/** 广场聊天消息（append-only）。 */
public record ChatMessage(
        long id,
        String roomId,
        long campusId,
        String senderId,
        String senderName,
        String senderRole,
        String content,
        String msgType,
        Long errandId,
        String errandStatus,
        String clientMsgId,
        int status,
        Instant createdAt) {
}
