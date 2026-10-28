package com.campusdash.presentation.realtime;

import com.campusdash.domain.chat.model.ChatMessage;
import com.campusdash.domain.chat.ports.ChatBroadcaster;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** 把聊天消息转成 {type:"chat.message", payload:{...}} 后广播给所有在线连接。 */
@Component
public class ChatWebSocketBroadcaster implements ChatBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketBroadcaster.class);
    private final WsSessionRegistry registry;
    private final ObjectMapper objectMapper;

    public ChatWebSocketBroadcaster(WsSessionRegistry registry, ObjectMapper objectMapper) {
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    @Override
    public void broadcast(ChatMessage message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", String.valueOf(message.id()));
        payload.put("roomId", message.roomId());
        payload.put("senderId", message.senderId());
        payload.put("senderName", message.senderName());
        payload.put("senderRole", message.senderRole());
        payload.put("content", message.content());
        payload.put("createdAt", message.createdAt().toString());
        payload.put("clientMsgId", message.clientMsgId());
        try {
            registry.broadcast(objectMapper.writeValueAsString(Map.of(
                    "type", "chat.message", "payload", payload)));
        } catch (Exception e) {
            log.warn("聊天消息序列化失败 id={}", message.id(), e);
        }
    }
}
