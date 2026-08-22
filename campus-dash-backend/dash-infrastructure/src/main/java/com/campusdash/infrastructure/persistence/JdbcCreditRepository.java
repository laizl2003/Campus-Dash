package com.campusdash.infrastructure.persistence;

import com.campusdash.domain.credit.model.CreditEvent;
import com.campusdash.domain.credit.model.CreditEventType;
import com.campusdash.domain.credit.model.CreditScore;
import com.campusdash.domain.credit.ports.CreditRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcCreditRepository implements CreditRepository {

    private final JdbcTemplate jdbc;

    public JdbcCreditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int scoreOf(long userId) {
        List<Integer> list = jdbc.queryForList(
                "SELECT score FROM credit_score WHERE user_id = ?", Integer.class, userId);
        return list.isEmpty() ? CreditEventType.BASE_SCORE : list.get(0);
    }

    @Override
    public Optional<CreditScore> find(long userId) {
        List<CreditScore> list = jdbc.query(
                "SELECT user_id, score, version FROM credit_score WHERE user_id = ?",
                (rs, n) -> new CreditScore(rs.getLong("user_id"), rs.getInt("score"), rs.getLong("version")),
                userId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /**
     * 事件流水 + 分数快照在同一事务（调用方的业务事务）里完成。
     *
     * 快照更新用 INSERT ... ON DUPLICATE KEY UPDATE：首次事件创建记录，
     * 后续事件增量更新。分数上下限的裁剪在 SQL 里用 LEAST/GREATEST 完成，
     * 与领域层 CreditScore.apply 的规则一致——两处规则必须同步修改，注释互指。
     */
    @Override
    public boolean applyEvent(CreditEvent event) {
        try {
            jdbc.update("""
                    INSERT INTO credit_event (id, biz_no, user_id, type, delta, ref_type, ref_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    event.id(), event.bizNo(), event.userId(), event.type().name(),
                    event.delta(), event.refType(), event.refId());
        } catch (DuplicateKeyException e) {
            // biz_no 已存在：重复事件，幂等跳过（不更新分数）
            return false;
        }
        jdbc.update("""
                INSERT INTO credit_score (user_id, score, version)
                VALUES (?, GREATEST(?, LEAST(?, ? + ?)), 1)
                ON DUPLICATE KEY UPDATE
                    score = GREATEST(?, LEAST(?, score + ?)),
                    version = version + 1
                """,
                event.userId(), CreditEventType.MIN_SCORE, CreditEventType.MAX_SCORE,
                CreditEventType.BASE_SCORE, event.delta(),
                CreditEventType.MIN_SCORE, CreditEventType.MAX_SCORE, event.delta());
        return true;
    }

    @Override
    public List<CreditEvent> recentEvents(long userId, int days, int limit) {
        return jdbc.query("""
                SELECT id, biz_no, user_id, type, delta, ref_type, ref_id, created_at
                  FROM credit_event
                 WHERE user_id = ? AND created_at >= ?
                 ORDER BY created_at DESC, id DESC
                 LIMIT ?
                """,
                (rs, n) -> new CreditEvent(
                        rs.getLong("id"), rs.getString("biz_no"), rs.getLong("user_id"),
                        CreditEventType.valueOf(rs.getString("type")), rs.getInt("delta"),
                        rs.getString("ref_type"), rs.getLong("ref_id"),
                        rs.getTimestamp("created_at").toInstant()),
                userId, Timestamp.from(Instant.now().minus(days, ChronoUnit.DAYS)), limit);
    }

    @Override
    public int windowDelta(long userId, int windowDays) {
        Integer sum = jdbc.queryForObject("""
                SELECT COALESCE(SUM(delta), 0) FROM credit_event
                 WHERE user_id = ? AND created_at >= ?
                """, Integer.class, userId,
                Timestamp.from(Instant.now().minus(windowDays, ChronoUnit.DAYS)));
        return sum == null ? 0 : sum;
    }

    @Override
    public int calibrateScores(int windowDays, int limit) {
        Timestamp windowStart = Timestamp.from(Instant.now().minus(windowDays, ChronoUnit.DAYS));
        List<CalibrationRow> rows = jdbc.query("""
                SELECT u.user_id,
                       COALESCE(s.score, ?) AS old_score,
                       GREATEST(?, LEAST(?, ? + COALESCE(SUM(e.delta), 0))) AS new_score
                  FROM (
                        SELECT user_id FROM credit_score
                        UNION
                        SELECT DISTINCT user_id FROM credit_event WHERE created_at >= ?
                       ) u
                  LEFT JOIN credit_score s ON s.user_id = u.user_id
                  LEFT JOIN credit_event e ON e.user_id = u.user_id AND e.created_at >= ?
                 GROUP BY u.user_id, s.score
                 HAVING old_score <> new_score
                 ORDER BY u.user_id
                 LIMIT ?
                """,
                (rs, n) -> new CalibrationRow(
                        rs.getLong("user_id"), rs.getInt("old_score"), rs.getInt("new_score")),
                CreditEventType.BASE_SCORE,
                CreditEventType.MIN_SCORE, CreditEventType.MAX_SCORE, CreditEventType.BASE_SCORE,
                windowStart, windowStart, limit);

        if (rows.isEmpty()) {
            return 0;
        }
        List<Object[]> args = new ArrayList<>(rows.size());
        for (CalibrationRow row : rows) {
            args.add(new Object[]{row.userId(), row.newScore()});
        }
        jdbc.batchUpdate("""
                INSERT INTO credit_score (user_id, score, version)
                VALUES (?, ?, 1)
                ON DUPLICATE KEY UPDATE
                    score = VALUES(score),
                    version = version + 1
                """, args);
        return rows.size();
    }

    private record CalibrationRow(long userId, int oldScore, int newScore) {}
}
