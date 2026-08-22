package com.campusdash.infrastructure.persistence;

import com.campusdash.domain.notify.ports.NotificationRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 站内消息仓储。
 *
 * 幂等靠唯一索引 uk_msg_user 而不是"先查再插"：
 * 后者在并发下有检查-使用竞态，两个线程都查到不存在然后都插入。
 * 让数据库的唯一索引来判定，捕获 DuplicateKeyException 即可。
 */
@Repository
public class JdbcNotificationRepository implements NotificationRepository {

    private final JdbcTemplate jdbc;

    public JdbcNotificationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insertIfAbsent(long id, String msgKey, long userId, long errandId,
                                  String type, String content) {
        try {
            jdbc.update("""
                    INSERT INTO notification (id, msg_key, user_id, errand_id, type, content)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, id, msgKey, userId, errandId, type, content);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }
}
