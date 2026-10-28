package com.campusdash.presentation.realtime;

import com.campusdash.application.usecase.chat.SendChatMessageUseCase;
import com.campusdash.domain.chat.model.ChatMessage;
import com.campusdash.shared.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WebSocket 连接处理器：服务端推送 + 客户端 chat.send。
 * ping/pong 保持原协议；其他未知消息忽略。
 */
@Component
@ConditionalOnProperty(name = "dash.ws.enabled", havingValue = "true", matchIfMissing = true)
public class WsHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(WsHandler.class);
    public static final String ATTR_USER_ID = "wsUserId";
    public static final String ATTR_TOKEN = "wsToken";

    private final WsSessionRegistry registry;
    private final SendChatMessageUseCase sendChatMessage;
    private final ObjectMapper objectMapper;

    public WsHandler(WsSessionRegistry registry, SendChatMessageUseCase sendChatMessage,
                     ObjectMapper objectMapper) {
        this.registry = registry;
        this.sendChatMessage = sendChatMessage;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String userId = (String) session.getAttributes().get(ATTR_USER_ID);
        if (userId == null) {
            try {
                session.close(CloseStatus.POLICY_VIOLATION);
            } catch (Exception ignored) {
            }
            return;
        }
        registry.add(userId, session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        if ("ping".equals(payload)) {
            session.sendMessage(new TextMessage("pong"));
            return;
        }

        String userId = (String) session.getAttributes().get(ATTR_USER_ID);
        if (userId == null) return;

        String clientMsgId = null;
        try {
            JsonNode node = objectMapper.readTree(payload);
            if (!"chat.send".equals(node.path("t").asText())) {
                return;
            }
            clientMsgId = node.path("clientMsgId").asText(null);
            ChatMessage sent = sendChatMessage.send(userId, clientMsgId, node.path("content").asText(""));
            log.debug("聊天消息已发送 id={} sender={}", sent.id(), userId);
        } catch (BizException e) {
            sendError(session, clientMsgId, e.code().name(), e.getMessage());
        } catch (Exception e) {
            log.warn("聊天消息处理失败 sender={}", userId, e);
            sendError(session, clientMsgId, "INTERNAL_ERROR", "消息发送失败，请稍后再试");
        }
    }

    private void sendError(WebSocketSession session, String clientMsgId, String code, String message) {
        if (session == null || !session.isOpen()) return;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("clientMsgId", clientMsgId);
        payload.put("code", code);
        payload.put("message", message);
        try {
            String json = objectMapper.writeValueAsString(Map.of(
                    "type", "chat.error", "payload", payload));
            synchronized (session) {
                session.sendMessage(new TextMessage(json));
            }
        } catch (Exception e) {
            log.debug("聊天错误帧发送失败", e);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String userId = (String) session.getAttributes().get(ATTR_USER_ID);
        if (userId != null) {
            registry.remove(userId, session);
        }
    }
}
