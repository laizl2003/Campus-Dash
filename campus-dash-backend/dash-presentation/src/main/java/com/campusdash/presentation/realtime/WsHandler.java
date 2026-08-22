package com.campusdash.presentation.realtime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * WebSocket 连接处理器：只管连接生命周期，消息内容全由服务端主动推。
 *
 * 不做"客户端订阅任务"协议：本项目量级下按 userId 广播足够，
 * 订阅协议会带来"订阅状态与业务状态漂移"这类新问题。
 *
 * 客户端可以发 ping，服务端回 pong（心跳保活，防中间设备断连）。
 */
@Component
@ConditionalOnProperty(name = "dash.ws.enabled", havingValue = "true", matchIfMissing = true)
public class WsHandler extends TextWebSocketHandler {

    public static final String ATTR_USER_ID = "wsUserId";

    private final WsSessionRegistry registry;

    public WsHandler(WsSessionRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long userId = (Long) session.getAttributes().get(ATTR_USER_ID);
        if (userId == null) {
            // 握手拦截器已挡掉未认证连接，这里是双保险
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
        if ("ping".equals(message.getPayload())) {
            session.sendMessage(new TextMessage("pong"));
        }
        // 其他客户端消息一律忽略：本协议只有服务端 -> 客户端方向有业务语义
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long userId = (Long) session.getAttributes().get(ATTR_USER_ID);
        if (userId != null) {
            registry.remove(userId, session);
        }
    }
}
