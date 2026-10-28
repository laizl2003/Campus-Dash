package com.campusdash.infrastructure.persistence;

import com.campusdash.domain.admin.ports.AdminUserQueryPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/** 管理端用户列表查询：只取 role=user，联查钱包余额 */
@Component
public class JdbcAdminUserQuery implements AdminUserQueryPort {

    private final JdbcTemplate jdbc;

    public JdbcAdminUserQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Page page(int offset, int limit) {
        List<Row> rows = jdbc.query("""
                SELECT u.user_id, u.username, u.last_login_at, u.login_status, u.last_seen_at, u.status, u.message_status,
                       COALESCE(wa.available, 0) AS balance
                  FROM user u
                  LEFT JOIN wallet_account wa ON wa.owner_id = u.user_id AND wa.owner_type = 'USER'
                 WHERE u.role = 'user'
                 ORDER BY u.created_at DESC
                 LIMIT ? OFFSET ?
                """, (rs, n) -> new Row(
                        rs.getString("user_id"),
                        rs.getString("username"),
                        rs.getTimestamp("last_login_at") == null ? null : rs.getTimestamp("last_login_at").toInstant(),
                        rs.getLong("balance"),
                        rs.getInt("login_status") == 1,
                        rs.getTimestamp("last_seen_at") == null ? null : rs.getTimestamp("last_seen_at").toInstant(),
                        rs.getInt("status") == 1,
                        rs.getInt("message_status") == 1),
                limit, offset);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM user WHERE role = 'user'", Long.class);
        return new Page(rows, total == null ? 0 : total);
    }
}
