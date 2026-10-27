package com.campusdash.infrastructure.persistence;

import com.campusdash.domain.voucher.model.Voucher;
import com.campusdash.domain.voucher.model.VoucherStatus;
import com.campusdash.domain.voucher.model.VoucherStock;
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
            rs.getString("description"),
            instant(rs, "last_change_time"));

    private static final RowMapper<Voucher> VOUCHER_MAPPER = (ResultSet rs, int n) -> new Voucher(
            rs.getLong("voucher_id"),
            rs.getString("user_id"),
            rs.getInt("voucher_type"),
            instant(rs, "expire_time"),
            VoucherStatus.valueOf(rs.getString("status").toUpperCase()),
            instant(rs, "created_at"));

    private final JdbcTemplate jdbc;

    public JdbcVoucherRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<VoucherStock> findAllStocks() {
        return jdbc.query("SELECT type, stock, description, last_change_time FROM voucher_stock ORDER BY type",
                STOCK_MAPPER);
    }

    @Override
    public Optional<VoucherStock> findStock(int type) {
        List<VoucherStock> rows = jdbc.query(
                "SELECT type, stock, description, last_change_time FROM voucher_stock WHERE type = ?",
                STOCK_MAPPER, type);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
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
                 WHERE type = ? AND stock > 0
                """, type);
    }

    @Override
    public void insert(Voucher voucher) {
        jdbc.update("""
                INSERT INTO voucher_record (voucher_id, user_id, voucher_type, expire_time, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, voucher.voucherId(), voucher.userId(), voucher.type(),
                timestamp(voucher.expireTime()), voucher.status().dbValue(), timestamp(voucher.createdAt()));
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
                UPDATE voucher_record SET status = 'expired'
                 WHERE voucher_id = ? AND status = 'available'
                """, voucherId);
    }

    @Override
    public int markUsed(long voucherId) {
        return jdbc.update("""
                UPDATE voucher_record SET status = 'used'
                 WHERE voucher_id = ? AND status = 'available'
                """, voucherId);
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
