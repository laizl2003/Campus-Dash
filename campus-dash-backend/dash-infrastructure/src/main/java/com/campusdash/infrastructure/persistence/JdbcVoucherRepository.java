package com.campusdash.infrastructure.persistence;

import com.campusdash.domain.voucher.model.Voucher;
import com.campusdash.domain.voucher.model.VoucherStatus;
import com.campusdash.domain.voucher.model.VoucherStock;
import com.campusdash.domain.voucher.model.VoucherType;
import com.campusdash.domain.voucher.model.VoucherUse;
import com.campusdash.domain.voucher.ports.VoucherRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 优惠券库存与领取记录 */
@Repository
public class JdbcVoucherRepository implements VoucherRepository {

    private static final RowMapper<VoucherStock> STOCK_MAPPER = (ResultSet rs, int n) -> new VoucherStock(
            rs.getInt("type"),
            rs.getInt("stock"),
            rs.getString("status"),
            rs.getLong("threshold_cents"),
            rs.getLong("discount_cents"),
            rs.getString("description"),
            instant(rs, "last_change_time"));

    private static final RowMapper<Voucher> VOUCHER_MAPPER = (ResultSet rs, int n) -> new Voucher(
            rs.getLong("voucher_id"),
            rs.getString("user_id"),
            rs.getInt("voucher_type"),
            instant(rs, "expire_time"),
            VoucherStatus.valueOf(rs.getString("status").toUpperCase()),
            instant(rs, "finish_at"),
            instant(rs, "created_at"));

    private final JdbcTemplate jdbc;

    public JdbcVoucherRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<VoucherStock> findAllStocks() {
        return jdbc.query("SELECT type, stock, status, threshold_cents, discount_cents, description, last_change_time "
                + "FROM voucher_stock ORDER BY type", STOCK_MAPPER);
    }

    @Override
    public Optional<VoucherStock> findStock(int type) {
        List<VoucherStock> rows = jdbc.query(
                "SELECT type, stock, status, threshold_cents, discount_cents, description, last_change_time "
                        + "FROM voucher_stock WHERE type = ?", STOCK_MAPPER, type);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public int updateDescription(int type, String description) {
        return jdbc.update("UPDATE voucher_stock SET description = ? WHERE type = ?", description, type);
    }

    @Override
    public void insertStock(VoucherStock stock) {
        jdbc.update("""
                INSERT INTO voucher_stock (type, stock, status, threshold_cents, discount_cents, description)
                VALUES (?, ?, ?, ?, ?, ?)
                """, stock.type(), stock.stock(), stock.status(), stock.thresholdCents(),
                stock.discountCents(), stock.description());
    }

    @Override
    public int updateStock(int type, int stock) {
        return jdbc.update("UPDATE voucher_stock SET stock = ?, last_change_time = NOW(3) WHERE type = ?",
                stock, type);
    }

    @Override
    public int updateStatus(int type, String status) {
        return jdbc.update("UPDATE voucher_stock SET status = ?, last_change_time = NOW(3) WHERE type = ?",
                status, type);
    }

    @Override
    public int deleteStock(int type) {
        return jdbc.update("DELETE FROM voucher_stock WHERE type = ?", type);
    }

    @Override
    public int deleteRecordsByType(int type) {
        return jdbc.update("DELETE FROM voucher_record WHERE voucher_type = ?", type);
    }

    @Override
    public int nextType() {
        Integer max = jdbc.queryForObject("SELECT COALESCE(MAX(type), 0) FROM voucher_stock", Integer.class);
        return (max == null ? 0 : max) + 1;
    }

    /**
     * 单条带条件的 UPDATE：并发下只有一个请求能把 stock 从 1 减到 0，
     * 其余请求影响行数为 0，直接判定"已抢光"。
     */
    @Override
    public int decrementStock(int type) {
        return jdbc.update("""
                UPDATE voucher_stock
                   SET stock = stock - 1, last_change_time = NOW(3)
                 WHERE type = ? AND status = 'ONLINE' AND stock > 0
                """, type);
    }

    @Override
    public void insert(Voucher voucher) {
        jdbc.update("""
                INSERT INTO voucher_record (voucher_id, user_id, voucher_type, expire_time, status, finish_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, voucher.voucherId(), voucher.userId(), voucher.type(),
                timestamp(voucher.expireTime()), voucher.status().dbValue(),
                timestamp(voucher.finishAt()), timestamp(voucher.createdAt()));
    }

    @Override
    public Optional<Voucher> findById(long voucherId) {
        List<Voucher> rows = jdbc.query(
                "SELECT * FROM voucher_record WHERE voucher_id = ?", VOUCHER_MAPPER, voucherId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public int countAvailable(String userId, int type, Instant now) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(1) FROM voucher_record
                 WHERE user_id = ? AND voucher_type = ? AND status = 'available' AND expire_time > ?
                """, Integer.class, userId, type, timestamp(now));
        return count == null ? 0 : count;
    }

    @Override
    public List<Voucher> listByUser(String userId, Instant now) {
        return jdbc.query("""
                SELECT * FROM voucher_record WHERE user_id = ? ORDER BY created_at DESC LIMIT 100
                """, VOUCHER_MAPPER, userId);
    }

    @Override
    public int markExpired(long voucherId) {
        return jdbc.update("""
                UPDATE voucher_record SET status = 'expired', finish_at = COALESCE(finish_at, NOW(3))
                 WHERE voucher_id = ? AND status = 'available'
                """, voucherId);
    }

    @Override
    public int markUsed(long voucherId) {
        return jdbc.update("""
                UPDATE voucher_record SET status = 'used', finish_at = COALESCE(finish_at, NOW(3))
                 WHERE voucher_id = ? AND status = 'available'
                """, voucherId);
    }

    @Override
    public int lockForErrand(long voucherId, String userId, long errandId, long runnerBearCents) {
        return jdbc.update("""
                UPDATE voucher_record
                   SET status = 'locked', used_errand_id = ?, runner_bear_cents = ?, finish_at = NOW(3)
                 WHERE voucher_id = ? AND user_id = ? AND status = 'available' AND expire_time > NOW(3)
                """, errandId, runnerBearCents, voucherId, userId);
    }

    @Override
    public Optional<VoucherUse> findUseByErrand(long errandId) {
        List<VoucherUse> rows = jdbc.query("""
                SELECT vr.voucher_id, vr.voucher_type, vr.runner_bear_cents,
                       COALESCE(vs.discount_cents, 0) AS discount_cents
                  FROM voucher_record vr
                  LEFT JOIN voucher_stock vs ON vs.type = vr.voucher_type
                 WHERE vr.used_errand_id = ? AND vr.status IN ('locked', 'used')
                 LIMIT 1
                """, (rs, n) -> new VoucherUse(
                        rs.getLong("voucher_id"),
                        rs.getInt("voucher_type"),
                        rs.getLong("discount_cents"),
                        rs.getLong("runner_bear_cents")), errandId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public int restoreByErrand(long errandId) {
        // 券退回时区分是否已过期：没过期恢复可用，已过期直接置 expired，
        // 不能把过期券重新变成可用券。
        return jdbc.update("""
                UPDATE voucher_record
                   SET status = IF(expire_time > NOW(3), 'available', 'expired'),
                       finish_at = IF(expire_time > NOW(3), NULL, NOW(3)),
                       used_errand_id = NULL,
                       runner_bear_cents = 0
                 WHERE used_errand_id = ? AND status IN ('locked', 'used')
                """, errandId);
    }

    @Override
    public int markUsedByErrand(long errandId) {
        return jdbc.update("""
                UPDATE voucher_record SET status = 'used', finish_at = COALESCE(finish_at, NOW(3))
                 WHERE used_errand_id = ? AND status = 'locked'
                """, errandId);
    }

    @Override
    public int expireOverdue(int limit) {
        return jdbc.update("""
                UPDATE voucher_record
                   SET status = 'expired', finish_at = NOW(3)
                 WHERE status = 'available' AND expire_time <= NOW(3)
                 ORDER BY expire_time
                 LIMIT ?
                """, limit);
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
