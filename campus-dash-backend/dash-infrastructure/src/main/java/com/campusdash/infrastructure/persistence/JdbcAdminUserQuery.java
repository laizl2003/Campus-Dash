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

    private static final String COLUMNS = """
            SELECT u.user_id, u.username, u.last_login_at, u.login_status, u.last_seen_at, u.status, u.message_status,
                   COALESCE(wa.available, 0) AS balance
              FROM user u
              LEFT JOIN wallet_account wa ON wa.owner_id = u.user_id AND wa.owner_type = 'USER'
             WHERE u.role = 'user'
            """;

    private static Row map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Row(
                rs.getString("user_id"),
                rs.getString("username"),
                rs.getTimestamp("last_login_at") == null ? null : rs.getTimestamp("last_login_at").toInstant(),
                rs.getLong("balance"),
                rs.getInt("login_status") == 1,
                rs.getTimestamp("last_seen_at") == null ? null : rs.getTimestamp("last_seen_at").toInstant(),
                rs.getInt("status") == 1,
                rs.getInt("message_status") == 1);
    }

    @Override
    public Page page(int offset, int limit) {
        List<Row> rows = jdbc.query(COLUMNS + " ORDER BY u.created_at DESC LIMIT ? OFFSET ?",
                JdbcAdminUserQuery::map, limit, offset);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM user WHERE role = 'user'", Long.class);
        return new Page(rows, total == null ? 0 : total);
    }

    @Override
    public Page page(int offset, int limit, List<String> onlineUserIds) {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM user WHERE role = 'user'", Long.class);
        if (onlineUserIds == null || onlineUserIds.isEmpty()) {
            return page(offset, limit);
        }
        // 在线用户排前面：用 CASE 生成排序键，分页仍在数据库里做，
        // 不再"取全量再内存排序"——那样会被 LIMIT 截断，导致老账号整条消失。
        String placeholders = String.join(",", java.util.Collections.nCopies(onlineUserIds.size(), "?"));
        String sql = COLUMNS
                + " ORDER BY CASE WHEN u.user_id IN (" + placeholders + ") THEN 0 ELSE 1 END, u.created_at DESC"
                + " LIMIT ? OFFSET ?";
        List<Object> args = new java.util.ArrayList<>(onlineUserIds);
        args.add(limit);
        args.add(offset);
        List<Row> rows = jdbc.query(sql, JdbcAdminUserQuery::map, args.toArray());
        return new Page(rows, total == null ? 0 : total);
    }
}
