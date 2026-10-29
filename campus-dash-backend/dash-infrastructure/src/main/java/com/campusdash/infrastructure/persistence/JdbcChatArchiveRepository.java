package com.campusdash.infrastructure.persistence;

import com.campusdash.domain.chat.ports.ChatArchivePort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 分事务批量归档；调用方循环直到返回 0。 */
@Repository
public class JdbcChatArchiveRepository implements ChatArchivePort {

    private static final String COLUMNS =
            "id, room_id, campus_id, sender_id, sender_name, sender_role, content, msg_type, " +
            "errand_id, errand_status, client_msg_id, status, created_at";

    /** 归档时按 errand 当前状态覆盖快照，避免归档瞬间卡片状态落后于任务。 */
    private static final String SELECT_COLUMNS = """
            cm.id, cm.room_id, cm.campus_id, cm.sender_id, cm.sender_name, cm.sender_role,
            cm.content, cm.msg_type, cm.errand_id,
            CASE WHEN cm.msg_type = 'ERRAND'
                 THEN COALESCE(e.status, cm.errand_status)
                 ELSE cm.errand_status
            END AS errand_status,
            cm.client_msg_id, cm.status, cm.created_at
            """;

    private final JdbcTemplate jdbc;

    public JdbcChatArchiveRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int archiveBefore(Instant cutoff, int batchSize) {
        int size = Math.max(1, Math.min(batchSize, 1000));
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM chat_message WHERE created_at < ? ORDER BY id ASC LIMIT ?",
                Long.class, Timestamp.from(cutoff), size);
        if (ids.isEmpty()) return 0;

        List<String> marks = new ArrayList<>(ids.size());
        for (int i = 0; i < ids.size(); i++) marks.add("?");
        String in = String.join(",", marks);
        Object[] args = ids.toArray();

        jdbc.update("INSERT IGNORE INTO history_message (" + COLUMNS + ") "
                + "SELECT " + SELECT_COLUMNS
                + " FROM chat_message cm LEFT JOIN errand e ON e.id = cm.errand_id"
                + " WHERE cm.id IN (" + in + ")", args);
        jdbc.update("DELETE FROM chat_message WHERE id IN (" + in + ")", args);
        return ids.size();
    }
}
