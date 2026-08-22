package com.campusdash.infrastructure.realtime;

import com.campusdash.domain.notify.ports.RealtimeNotifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * WebSocket 关闭时的空实现（放 infrastructure：application 层不依赖 boot autoconfigure）。
 * 开关粒度用 @ConditionalOnProperty 而不是 @Profile：生产也能临时关掉 WS 做对照。
 */
@Component
@ConditionalOnProperty(name = "dash.ws.enabled", havingValue = "false")
public class NoopRealtimeNotifier implements RealtimeNotifier {

    @Override
    public void errandStatusChanged(long errandId, long publisherId, Long grabberId,
                                    String status, int round) {
    }

    @Override
    public void notificationArrived(long userId, long errandId, String type, String content) {
    }

    @Override
    public void creditChanged(long userId, int newScore, int delta, String reason) {
    }
}
