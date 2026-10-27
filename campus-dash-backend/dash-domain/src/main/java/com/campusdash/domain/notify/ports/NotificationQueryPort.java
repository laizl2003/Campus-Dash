package com.campusdash.domain.notify.ports;

import java.time.Instant;
import java.util.List;

/** 站内消息读端口。消费落库在 NotificationRepository，读取在这里 */
public interface NotificationQueryPort {

    List<NotificationView> list(String userId, int page, int size);

    int unreadCount(String userId);

    /** 把某条消息标记为已读；只有归属该用户时才生效，返回影响行数 */
    int markRead(String userId, long notificationId);

    record NotificationView(long id, long errandId, String type, String content, Instant time, boolean read) {}
}
