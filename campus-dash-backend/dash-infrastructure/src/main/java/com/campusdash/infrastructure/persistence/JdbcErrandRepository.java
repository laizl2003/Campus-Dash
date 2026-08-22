package com.campusdash.infrastructure.persistence;

import com.campusdash.domain.errand.model.Errand;
import com.campusdash.domain.errand.model.ErrandStatus;
import com.campusdash.domain.errand.model.ErrandType;
import com.campusdash.domain.errand.ports.ErrandRepository;
import com.campusdash.shared.Money;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 任务仓储的 JDBC 实现。
 *
 * 一期用 JdbcTemplate 而不是 ORM：抢单的核心是带条件的 CAS 语句，
 * 需要精确控制 SQL 形态、索引命中，并直接拿到 affectedRows 做判定。
 * SQL 明文写出来，教学上也能一眼看清并发控制发生在哪里。
 */
@Repository
public class JdbcErrandRepository implements ErrandRepository {

    private final JdbcTemplate jdbc;

    public JdbcErrandRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Errand> MAPPER = (rs, n) -> Errand.rehydrate(
            rs.getLong("id"),
            rs.getLong("campus_id"),
            rs.getLong("publisher_id"),
            ErrandType.valueOf(rs.getString("type")),
            rs.getString("title"),
            Money.ofCents(rs.getLong("reward_amount")),
            rs.getInt("slot_total"),
            rs.getObject("grabber_id") == null ? null : rs.getLong("grabber_id"),
            ErrandStatus.valueOf(rs.getString("status")),
            rs.getInt("slot_taken"),
            rs.getInt("round"),
            rs.getLong("version"),
            rs.getTimestamp("locked_at") == null ? null : rs.getTimestamp("locked_at").toInstant(),
            rs.getTimestamp("delivered_at") == null ? null : rs.getTimestamp("delivered_at").toInstant());

    @Override
    public void insert(Errand errand) {
        jdbc.update("""
                INSERT INTO errand (id, campus_id, publisher_id, type, title, reward_amount,
                                    slot_total, slot_taken, status, round, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                errand.id(), errand.campusId(), errand.publisherId(), errand.type().name(),
                errand.title(), errand.reward().cents(), errand.slotTotal(), errand.slotTaken(),
                errand.status().name(), errand.round(), errand.version());
    }

    @Override
    public Optional<Errand> findById(long errandId) {
        List<Errand> list = jdbc.query("SELECT * FROM errand WHERE id = ?", MAPPER, errandId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /**
     * 抢单的核心 CAS。
     *
     * 三个条件缺一不可：
     *   id = ?                   定位到行（走主键，不会扫表）
     *   status = 'PUBLISHED'     状态必须还是可抢的
     *   version = ?              没有被其他事务改动过
     *
     * affectedRows = 0 就是抢单失败，不需要额外查询判断原因。
     * slot_taken + 1 有 CHECK 约束兜底，即使代码写错也不会超过 slot_total。
     */
    @Override
    public int casLockForRunner(long errandId, long runnerId, long expectedVersion) {
        return jdbc.update("""
                UPDATE errand
                   SET status = 'LOCKED',
                       grabber_id = ?,
                       slot_taken = slot_taken + 1,
                       locked_at = NOW(3),
                       version = version + 1
                 WHERE id = ?
                   AND status = 'PUBLISHED'
                   AND version = ?
                   AND slot_taken < slot_total
                """, runnerId, errandId, expectedVersion);
    }

    @Override
    public int casPublish(long errandId, long expectedVersion) {
        return jdbc.update("""
                UPDATE errand
                   SET status = 'PUBLISHED', version = version + 1
                 WHERE id = ? AND status = 'DRAFT' AND version = ?
                """, errandId, expectedVersion);
    }

    /** 确认接单：条件里带 grabber_id，保证只有当前抢中者本人能确认 */
    @Override
    public int casAccept(long errandId, long runnerId, long expectedVersion) {
        return jdbc.update("""
                UPDATE errand
                   SET status = 'ACCEPTED', version = version + 1
                 WHERE id = ? AND status = 'LOCKED' AND grabber_id = ? AND version = ?
                """, errandId, runnerId, expectedVersion);
    }

    /**
     * 超时流转：LOCKED -> LOCKED，round+1。
     *
     * 条件里同时带 version 与 round 是幂等的关键：
     *   - version 保证任务没被其他操作改过（比如跑腿已确认）
     *   - round 保证这条消息属于当前轮次，上一轮的延迟消息到达时 round 不匹配，
     *     CAS 直接失败，不会把已经流转给 C 的任务又流转一次
     * slot_taken 不动：名额还是那一个，只是占用者换人。
     */
    @Override
    public int casTransferToNext(long errandId, long nextRunnerId, long expectedVersion, int expectedRound) {
        return jdbc.update("""
                UPDATE errand
                   SET grabber_id = ?,
                       round = round + 1,
                       locked_at = NOW(3),
                       version = version + 1
                 WHERE id = ? AND status = 'LOCKED' AND version = ? AND round = ?
                """, nextRunnerId, errandId, expectedVersion, expectedRound);
    }

    /** 候选队列空，回退重新开放：名额必须还回去，否则任务显示可抢但实际满 */
    @Override
    public int casRevertToPublished(long errandId, long expectedVersion, int expectedRound) {
        return jdbc.update("""
                UPDATE errand
                   SET status = 'PUBLISHED',
                       grabber_id = NULL,
                       slot_taken = GREATEST(slot_taken - 1, 0),
                       round = round + 1,
                       locked_at = NULL,
                       version = version + 1
                 WHERE id = ? AND status = 'LOCKED' AND version = ? AND round = ?
                """, errandId, expectedVersion, expectedRound);
    }

    /**
     * 兜底扫描：走 idx_timeout_scan(status, locked_at) 索引，不会全表扫。
     * 这是"主通道 + 兜底"模式的兜底侧——MQ 消息万一丢了，
     * 任务不能永久卡在 LOCKED 让资金一直冻结。
     */
    @Override
    public List<Errand> findConfirmTimeout(long timeoutSeconds, int limit) {
        return jdbc.query("""
                SELECT * FROM errand
                 WHERE status = 'LOCKED'
                   AND locked_at < DATE_SUB(NOW(3), INTERVAL ? SECOND)
                 ORDER BY locked_at
                 LIMIT ?
                """, MAPPER, timeoutSeconds, limit);
    }

    @Override
    public int casPickUp(long errandId, long runnerId, long expectedVersion) {
        return jdbc.update("""
                UPDATE errand
                   SET status = 'PICKED_UP', version = version + 1
                 WHERE id = ? AND status = 'ACCEPTED' AND grabber_id = ? AND version = ?
                """, errandId, runnerId, expectedVersion);
    }

    /** 送达：写 delivered_at 供自动结算扫描定位，用 NOW(3) 让时间以数据库为准 */
    @Override
    public int casDeliver(long errandId, long runnerId, long expectedVersion) {
        return jdbc.update("""
                UPDATE errand
                   SET status = 'DELIVERED', delivered_at = NOW(3), version = version + 1
                 WHERE id = ? AND status = 'PICKED_UP' AND grabber_id = ? AND version = ?
                """, errandId, runnerId, expectedVersion);
    }

    /**
     * 结算：DELIVERED -> SETTLED。
     * 这里不带 version 条件而只带 status，是有意的：
     * 自动结算与人工确认可能并发，两者读到的 version 可能都是旧的，
     * 但"只有一个能把 DELIVERED 改成 SETTLED"这一条就足够保证不重复结算。
     * 真正的资金幂等还有 escrow 状态 CAS 与 ledger 唯一索引两道。
     */
    @Override
    public int casSettle(long errandId, long expectedVersion) {
        return jdbc.update("""
                UPDATE errand
                   SET status = 'SETTLED', version = version + 1
                 WHERE id = ? AND status = 'DELIVERED'
                """, errandId);
    }

    @Override
    public int casSettleFromDispute(long errandId, long expectedVersion) {
        return jdbc.update("""
                UPDATE errand
                   SET status = 'SETTLED', version = version + 1
                 WHERE id = ? AND status = 'DISPUTED'
                """, errandId);
    }

    @Override
    public int casRefundFromDispute(long errandId, long expectedVersion) {
        return jdbc.update("""
                UPDATE errand
                   SET status = 'REFUNDED', version = version + 1
                 WHERE id = ? AND status = 'DISPUTED'
                """, errandId);
    }

    /** 取消：只有还没人抢中（DRAFT/PUBLISHED）才允许，名额清零 */
    @Override
    public int casCancel(long errandId, long expectedVersion) {
        return jdbc.update("""
                UPDATE errand
                   SET status = 'CANCELLED', slot_taken = 0, version = version + 1
                 WHERE id = ? AND status IN ('DRAFT', 'PUBLISHED') AND version = ?
                """, errandId, expectedVersion);
    }

    @Override
    public int casDispute(long errandId, long expectedVersion) {
        return jdbc.update("""
                UPDATE errand
                   SET status = 'DISPUTED', version = version + 1
                 WHERE id = ? AND status IN ('ACCEPTED', 'PICKED_UP', 'DELIVERED') AND version = ?
                """, errandId, expectedVersion);
    }

    /** 自动结算扫描：走 idx_autosettle_scan(status, delivered_at) */
    @Override
    public List<Errand> findAutoSettleDue(long autoSettleSeconds, int limit) {
        return jdbc.query("""
                SELECT * FROM errand
                 WHERE status = 'DELIVERED'
                   AND delivered_at < DATE_SUB(NOW(3), INTERVAL ? SECOND)
                 ORDER BY delivered_at
                 LIMIT ?
                """, MAPPER, autoSettleSeconds, limit);
    }

    @Override
    public void appendStatusLog(long errandId, ErrandStatus from, ErrandStatus to, int round, long operatorId) {
        jdbc.update("""
                INSERT INTO errand_status_log (campus_id, errand_id, from_status, to_status, round, operator_id)
                VALUES ((SELECT campus_id FROM errand WHERE id = ?), ?, ?, ?, ?, ?)
                """, errandId, errandId, from.name(), to.name(), round, operatorId);
    }
}
