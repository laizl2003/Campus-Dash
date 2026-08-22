package com.campusdash.domain.notify.ports;

import java.time.Instant;
import java.util.List;

/** 站内消息读端口。消费落库在 NotificationRepository，读取在这里 */
public interface NotificationQueryPort {

    List<NotificationView> list(long userId, int page, int size);

    int unreadCount(long userId);

    record NotificationView(long id, long errandId, String type, String content, Instant time) {}
}
