package com.campusdash.infrastructure.persistence;

import com.campusdash.domain.auth.model.UserAccount;
import com.campusdash.domain.auth.ports.UserRepository;
import com.campusdash.shared.BizException;
import com.campusdash.shared.ErrorCode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 账号表读写。表名 user 是 MySQL 关键字附近的名字，统一加反引号避免歧义。 */
@Repository
public class JdbcUserRepository implements UserRepository {

    private static final String COLUMNS = "user_id, username, phone, password_hash, password_updated_at, "
            + "created_at, updated_at, role, avatar_url, campus_id, credit_score, status, "
            + "last_login_at, last_login_ip, login_fail_count, remark";

    private static final RowMapper<UserAccount> MAPPER = (ResultSet rs, int rowNum) -> new UserAccount(
            rs.getString("user_id"),
            rs.getString("username"),
            rs.getString("phone"),
            rs.getString("password_hash"),
            instant(rs, "password_updated_at"),
            instant(rs, "created_at"),
            instant(rs, "updated_at"),
            rs.getString("role"),
            rs.getString("avatar_url"),
            rs.getLong("campus_id"),
            rs.getInt("credit_score"),
            rs.getInt("status") == 1,
            instant(rs, "last_login_at"),
            rs.getString("last_login_ip"),
            rs.getInt("login_fail_count"),
            rs.getString("remark"));

    private final JdbcTemplate jdbc;

    public JdbcUserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UserAccount> findByUsername(String username) {
        return one("SELECT " + COLUMNS + " FROM `user` WHERE username = ?", username);
    }

    @Override
    public Optional<UserAccount> findById(String userId) {
        return one("SELECT " + COLUMNS + " FROM `user` WHERE user_id = ?", userId);
    }

    @Override
    public Optional<UserAccount> findByPhone(String phone) {
        return one("SELECT " + COLUMNS + " FROM `user` WHERE phone = ?", phone);
    }

    @Override
    public boolean existsByUsername(String username) {
        Integer count = jdbc.queryForObject("SELECT COUNT(1) FROM `user` WHERE username = ?", Integer.class, username);
        return count != null && count > 0;
    }

    @Override
    public boolean existsByPhone(String phone) {
        Integer count = jdbc.queryForObject("SELECT COUNT(1) FROM `user` WHERE phone = ?", Integer.class, phone);
        return count != null && count > 0;
    }

    @Override
    public void insert(UserAccount account) {
        try {
            jdbc.update("INSERT INTO `user` (" + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    account.userId(),
                    account.username(),
                    account.phone(),
                    account.passwordHash(),
                    timestamp(account.passwordUpdatedAt()),
                    timestamp(account.createdAt()),
                    timestamp(account.updatedAt()),
                    account.role(),
                    account.avatarUrl(),
                    account.campusId(),
                    account.creditScore(),
                    account.enabled() ? 1 : 0,
                    timestamp(account.lastLoginAt()),
                    account.lastLoginIp(),
                    account.loginFailCount(),
                    account.remark());
        } catch (DuplicateKeyException e) {
            // 并发注册时 existsBy 会同时通过，唯一索引才是最终防线
            String message = e.getMessage() == null ? "" : e.getMessage();
            if (message.contains("uk_user_phone")) {
                throw new BizException(ErrorCode.PHONE_TAKEN, "phone=" + account.phone());
            }
            if (message.contains("uk_user_username")) {
                throw new BizException(ErrorCode.USERNAME_TAKEN, "username=" + account.username());
            }
            throw new BizException(ErrorCode.USERNAME_TAKEN, "唯一约束冲突");
        }
    }

    @Override
    public boolean deleteById(String userId) {
        return jdbc.update("DELETE FROM `user` WHERE user_id = ?", userId) > 0;
    }

    @Override
    public void markLoginSuccess(String userId, Instant at, String ip) {
        jdbc.update("UPDATE `user` SET last_login_at = ?, last_login_ip = ?, login_fail_count = 0 WHERE user_id = ?",
                timestamp(at), ip == null ? "" : ip, userId);
    }

    @Override
    public void markLoginFailure(String userId) {
        jdbc.update("UPDATE `user` SET login_fail_count = login_fail_count + 1 WHERE user_id = ?", userId);
    }

    @Override
    public void updatePassword(String userId, String passwordHash, Instant at) {
        Timestamp now = timestamp(at);
        jdbc.update("UPDATE `user` SET password_hash = ?, password_updated_at = ?, updated_at = ? WHERE user_id = ?",
                passwordHash, now, now, userId);
    }

    private Optional<UserAccount> one(String sql, Object value) {
        List<UserAccount> rows = jdbc.query(sql, MAPPER, value);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
