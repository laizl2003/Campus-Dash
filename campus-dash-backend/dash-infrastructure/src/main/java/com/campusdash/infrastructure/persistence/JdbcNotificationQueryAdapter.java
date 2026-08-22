package com.campusdash.infrastructure.persistence;

import com.campusdash.domain.notify.ports.NotificationQueryPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class JdbcNotificationQueryAdapter implements NotificationQueryPort {

    private final JdbcTemplate jdbc;

    public JdbcNotificationQueryAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<NotificationView> list(long userId, int page, int size) {
        return jdbc.query("""
                SELECT id, errand_id, type, content, created_at
                  FROM notification
                 WHERE user_id = ?
                 ORDER BY created_at DESC, id DESC
                 LIMIT ? OFFSET ?
                """, (rs, n) -> new NotificationView(
                        rs.getLong("id"), rs.getLong("errand_id"), rs.getString("type"),
                        rs.getString("content"), rs.getTimestamp("created_at").toInstant()),
                userId, size, page * size);
    }

    @Override
    public int unreadCount(long userId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE user_id = ? AND read_flag = 0",
                Integer.class, userId);
        return n == null ? 0 : n;
    }
}
