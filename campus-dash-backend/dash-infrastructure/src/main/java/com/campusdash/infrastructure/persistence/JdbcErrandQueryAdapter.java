package com.campusdash.infrastructure.persistence;

import com.campusdash.domain.errand.model.Errand;
import com.campusdash.domain.errand.ports.ErrandQueryPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public class JdbcErrandQueryAdapter implements ErrandQueryPort {

    /** 读模型的字段映射与写仓储共用一套 rehydrate 逻辑，避免两边漂移 */
    private static final RowMapper<Errand> MAPPER = (rs, n) -> Errand.rehydrate(
            rs.getLong("id"), rs.getLong("campus_id"), rs.getLong("publisher_id"),
            com.campusdash.domain.errand.model.ErrandType.valueOf(rs.getString("type")),
            rs.getString("title"),
            com.campusdash.shared.Money.ofCents(rs.getLong("reward_amount")),
            rs.getInt("slot_total"),
            rs.getObject("grabber_id") == null ? null : rs.getLong("grabber_id"),
            com.campusdash.domain.errand.model.ErrandStatus.valueOf(rs.getString("status")),
            rs.getInt("slot_taken"), rs.getInt("round"), rs.getLong("version"),
            rs.getTimestamp("locked_at") == null ? null : rs.getTimestamp("locked_at").toInstant(),
            rs.getTimestamp("delivered_at") == null ? null : rs.getTimestamp("delivered_at").toInstant());

    private final JdbcTemplate jdbc;

    public JdbcErrandQueryAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Errand> list(long campusId, String status, int page, int size) {
        return jdbc.query("""
                SELECT * FROM errand
                 WHERE campus_id = ? AND status = ?
                 ORDER BY created_at DESC
                 LIMIT ? OFFSET ?
                """, MAPPER, campusId, status, size, page * size);
    }

    @Override
    public List<CursorItem> listByCursor(long campusId, String status, Instant beforeCreatedAt, Long beforeId, int size) {
        if (beforeCreatedAt == null || beforeId == null) {
            return jdbc.query("""
                    SELECT * FROM errand
                     WHERE campus_id = ? AND status = ?
                     ORDER BY created_at DESC, id DESC
                     LIMIT ?
                    """, (rs, n) -> new CursorItem(MAPPER.mapRow(rs, n),
                            rs.getTimestamp("created_at").toInstant()),
                    campusId, status, size);
        }
        return jdbc.query("""
                SELECT * FROM errand
                 WHERE campus_id = ? AND status = ?
                   AND (created_at < ? OR (created_at = ? AND id < ?))
                 ORDER BY created_at DESC, id DESC
                 LIMIT ?
                """, (rs, n) -> new CursorItem(MAPPER.mapRow(rs, n),
                        rs.getTimestamp("created_at").toInstant()),
                campusId, status,
                java.sql.Timestamp.from(beforeCreatedAt), java.sql.Timestamp.from(beforeCreatedAt),
                beforeId, size);
    }

    @Override
    public List<Errand> listByPublisher(long publisherId, int page, int size) {
        return jdbc.query("""
                SELECT * FROM errand
                 WHERE publisher_id = ?
                 ORDER BY created_at DESC
                 LIMIT ? OFFSET ?
                """, MAPPER, publisherId, size, page * size);
    }

    @Override
    public List<Errand> listByRunner(long runnerId, int page, int size) {
        // 抢中过的任务：grab_record 里每一轮都有记录，用 DISTINCT 去重
        return jdbc.query("""
                SELECT e.* FROM errand e
                  JOIN (SELECT DISTINCT errand_id FROM grab_record WHERE runner_id = ?) g
                    ON g.errand_id = e.id
                 ORDER BY e.created_at DESC
                 LIMIT ? OFFSET ?
                """, MAPPER, runnerId, size, page * size);
    }

    @Override
    public List<StatusChange> statusLog(long campusId, long errandId) {
        return jdbc.query("""
                SELECT created_at, from_status, to_status, round, operator_id
                  FROM errand_status_log
                 WHERE campus_id = ? AND errand_id = ?
                 ORDER BY created_at, id
                """, (rs, n) -> new StatusChange(
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getString("from_status"), rs.getString("to_status"),
                        rs.getInt("round"), rs.getLong("operator_id")), campusId, errandId);
    }

    @Override
    public List<Long> sampleIds(int limit) {
        return jdbc.queryForList("SELECT id FROM errand ORDER BY RAND() LIMIT ?", Long.class, limit);
    }

    @Override
    public int countOngoingByRunner(long runnerId) {
        // 在途 = 已确认接单但还没送达的任务（ACCEPTED/PICKED_UP）。
        // LOCKED 不算：还没确认，可能超时流转走
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM errand
                 WHERE grabber_id = ? AND status IN ('ACCEPTED', 'PICKED_UP')
                """, Integer.class, runnerId);
        return n == null ? 0 : n;
    }
}
