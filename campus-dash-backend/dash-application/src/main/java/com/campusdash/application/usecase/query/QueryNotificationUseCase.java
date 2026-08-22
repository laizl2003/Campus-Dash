package com.campusdash.application.usecase.query;

import com.campusdash.domain.notify.ports.NotificationQueryPort;
import org.springframework.stereotype.Service;

import java.util.List;

/** 站内消息查询：列表 + 未读数 */
@Service
public class QueryNotificationUseCase {

    private final NotificationQueryPort queryPort;

    public QueryNotificationUseCase(NotificationQueryPort queryPort) {
        this.queryPort = queryPort;
    }

    public List<NotificationQueryPort.NotificationView> list(long userId, int page, int size) {
        return queryPort.list(userId, Math.max(page, 0), Math.min(Math.max(size, 1), 50));
    }

    public int unread(long userId) {
        return queryPort.unreadCount(userId);
    }
}
