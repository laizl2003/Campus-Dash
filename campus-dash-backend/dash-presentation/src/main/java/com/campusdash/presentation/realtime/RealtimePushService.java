package com.campusdash.presentation.realtime;

import com.campusdash.domain.notify.ports.RealtimeNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;

/**
 * WebSocket 推送实现（dash.ws.enabled=true 时装配）。
 *
 * 推送协议：JSON {type, payload}，type 三种：
 *   errand.status    任务状态变更
 *   notification.new 站内消息到达
 *   credit.changed   信用分变更
 *
 * 推送失败只记日志不重试：实时推送是体验优化，事实以 DB 为准，
 * 前端有轮询兜底。为推送做重试队列是本末倒置。
 */
@Component
@ConditionalOnProperty(name = "dash.ws.enabled", havingValue = "true", matchIfMissing = true)
public class RealtimePushService implements RealtimeNotifier {

    private static final Logger log = LoggerFactory.getLogger(RealtimePushService.class);

    private final WsSessionRegistry registry;

    public RealtimePushService(WsSessionRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void errandStatusChanged(long errandId, long publisherId, Long grabberId,
                                    String status, int round) {
        String msg = String.format(
                "{\"type\":\"errand.status\",\"payload\":{\"errandId\":\"%d\",\"status\":\"%s\",\"round\":%d}}",
                errandId, status, round);
        push(publisherId, msg);
        if (grabberId != null && grabberId != publisherId) {
            push(grabberId, msg);
        }
    }

    @Override
    public void notificationArrived(long userId, long errandId, String type, String content) {
        String msg = String.format(
                "{\"type\":\"notification.new\",\"payload\":{\"errandId\":\"%d\",\"noticeType\":\"%s\",\"content\":\"%s\"}}",
                errandId, type, escape(content));
        push(userId, msg);
    }

    @Override
    public void creditChanged(long userId, int newScore, int delta, String reason) {
        String msg = String.format(
                "{\"type\":\"credit.changed\",\"payload\":{\"score\":%d,\"delta\":%d,\"reason\":\"%s\"}}",
                newScore, delta, escape(reason));
        push(userId, msg);
    }

    private void push(long userId, String json) {
        for (WebSocketSession session : registry.of(userId)) {
            if (!session.isOpen()) {
                continue;
            }
            try {
                // 同一 session 的并发发送要同步：WebSocketSession.sendMessage 非线程安全
                synchronized (session) {
                    session.sendMessage(new TextMessage(json));
                }
            } catch (IOException e) {
                log.debug("推送失败 userId={}（连接可能已断）", userId);
            }
        }
    }

    private String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
