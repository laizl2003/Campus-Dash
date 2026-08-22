package com.campusdash.infrastructure.persistence;

import com.campusdash.domain.errand.ports.LocalMessageRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
public class JdbcLocalMessageRepository implements LocalMessageRepository {

    private final JdbcTemplate jdbc;

    public JdbcLocalMessageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 登记待发消息。撞 uk_msg_key 说明这一轮已经登记过，返回 false 而不是抛异常——
     * 重复登记是预期情况（消息重投、请求重试），不该让业务事务回滚。
     */
    @Override
    public boolean enqueue(long id, String msgKey, String topic, String payload, Instant deliverAt) {
        try {
            jdbc.update("""
                    INSERT INTO local_message (id, msg_key, topic, payload, deliver_at, status, next_retry_at)
                    VALUES (?, ?, ?, ?, ?, 'PENDING', ?)
                    """, id, msgKey, topic, payload,
                    Timestamp.from(deliverAt), Timestamp.from(deliverAt));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public void markSent(String msgKey) {
        jdbc.update("UPDATE local_message SET status = 'SENT' WHERE msg_key = ?", msgKey);
    }

    @Override
    public List<PendingMessage> findPending(int limit) {
        return jdbc.query("""
                SELECT id, msg_key, topic, payload, deliver_at, retry_count
                  FROM local_message
                 WHERE status = 'PENDING' AND next_retry_at <= NOW(3)
                 ORDER BY next_retry_at
                 LIMIT ?
                """, (rs, n) -> new PendingMessage(
                        rs.getLong("id"), rs.getString("msg_key"), rs.getString("topic"),
                        rs.getString("payload"), rs.getTimestamp("deliver_at").toInstant(),
                        rs.getInt("retry_count")), limit);
    }

    /**
     * 重试失败处理：指数退避（2^retry 秒，上限 5 分钟），超过上限转 DEAD 人工介入。
     * 不无限重试是为了避免一条坏消息把 worker 拖死。
     */
    @Override
    public void markRetry(String msgKey, int maxRetry) {
        jdbc.update("""
                UPDATE local_message
                   SET retry_count = retry_count + 1,
                       status = CASE WHEN retry_count + 1 >= ? THEN 'DEAD' ELSE 'PENDING' END,
                       next_retry_at = DATE_ADD(NOW(3),
                           INTERVAL LEAST(POW(2, retry_count + 1), 300) SECOND)
                 WHERE msg_key = ?
                """, maxRetry, msgKey);
    }
}
