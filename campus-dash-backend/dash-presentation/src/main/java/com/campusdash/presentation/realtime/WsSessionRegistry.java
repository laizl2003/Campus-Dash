package com.campusdash.presentation.realtime;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 在线连接注册表：userId -> 该用户的所有连接（多端登录）。
 *
 * 用 CopyOnWriteArraySet：推送是遍历读，连接增删是低频写，
 * 读多写少场景下它比同步集合更合适。
 */
@Component
public class WsSessionRegistry {

    private final ConcurrentHashMap<String, Set<WebSocketSession>> sessions = new ConcurrentHashMap<>();

    public void add(String userId, WebSocketSession session) {
        sessions.computeIfAbsent(userId, k -> new CopyOnWriteArraySet<>()).add(session);
    }

    public void remove(String userId, WebSocketSession session) {
        Set<WebSocketSession> set = sessions.get(userId);
        if (set != null) {
            set.remove(session);
            if (set.isEmpty()) {
                sessions.remove(userId, set);
            }
        }
    }

    public Set<WebSocketSession> of(String userId) {
        return sessions.getOrDefault(userId, Set.of());
    }

    public int onlineUsers() {
        return sessions.size();
    }

    /** 当前有 WebSocket 连接的用户 id 快照，可用于在线人数/在线列表。 */
    public Set<String> onlineUserIds() {
        return Set.copyOf(sessions.keySet());
    }

    /** 当前浏览器登出：只关闭携带该 token 的连接，不影响同用户其他浏览器。 */
    public void closeToken(String userId, String token) {
        Set<WebSocketSession> set = sessions.get(userId);
        if (set == null || token == null) return;
        for (WebSocketSession session : set) {
            if (!token.equals(session.getAttributes().get(WsHandler.ATTR_TOKEN))) continue;
            try {
                if (session.isOpen()) session.close(org.springframework.web.socket.CloseStatus.NORMAL);
            } catch (Exception ignored) {
                // 连接可能已断；remove 由 afterConnectionClosed 兜底
            }
        }
    }

    /** 强制下线：立即关闭并移除该用户的全部连接。 */
    public void closeUser(String userId) {
        Set<WebSocketSession> set = sessions.remove(userId);
        if (set == null) return;
        for (WebSocketSession session : set) {
            try {
                if (session.isOpen()) session.close(org.springframework.web.socket.CloseStatus.NORMAL);
            } catch (Exception ignored) {
                // 连接可能已断；注册表已删 key，下一次心跳/重连自愈
            }
        }
    }

    /** 广播给所有在线连接（管理端事件量很低，直接遍历即可） */
    public void broadcast(String json) {
        sessions.values().forEach(set -> set.forEach(session -> {
            if (!session.isOpen()) return;
            try {
                synchronized (session) {
                    session.sendMessage(new org.springframework.web.socket.TextMessage(json));
                }
            } catch (java.io.IOException ignored) {
                // 连接可能已断，下一次心跳/重连会自愈
            }
        }));
    }
}
