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

    private final ConcurrentHashMap<Long, Set<WebSocketSession>> sessions = new ConcurrentHashMap<>();

    public void add(long userId, WebSocketSession session) {
        sessions.computeIfAbsent(userId, k -> new CopyOnWriteArraySet<>()).add(session);
    }

    public void remove(long userId, WebSocketSession session) {
        Set<WebSocketSession> set = sessions.get(userId);
        if (set != null) {
            set.remove(session);
            if (set.isEmpty()) {
                sessions.remove(userId, set);
            }
        }
    }

    public Set<WebSocketSession> of(long userId) {
        return sessions.getOrDefault(userId, Set.of());
    }

    public int onlineUsers() {
        return sessions.size();
    }
}
