package com.campusdash.infrastructure.persistence;

import com.campusdash.domain.admin.ports.AdminArbitrationQueryPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/** 仲裁记录：直接复用 fund_audit_log，不新增表 */
@Component
public class JdbcAdminArbitrationQuery implements AdminArbitrationQueryPort {

    private final JdbcTemplate jdbc;

    public JdbcAdminArbitrationQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Page page(int offset, int limit) {
        List<Row> rows = jdbc.query("""
                SELECT a.id, a.errand_id, e.title AS errand_title,
                       COALESCE(u.username, a.operator_id) AS operator_name,
                       COALESCE(JSON_UNQUOTE(JSON_EXTRACT(a.detail, '$.favor')), '') AS favor,
                       COALESCE(CAST(JSON_EXTRACT(a.detail, '$.amount') AS SIGNED), 0) AS amount_cents,
                       a.result, a.created_at
                  FROM fund_audit_log a
                  LEFT JOIN user u ON u.user_id = a.operator_id
                  LEFT JOIN errand e ON e.id = a.errand_id
                 WHERE a.action = 'ARBITRATE'
                 ORDER BY a.created_at DESC
                 LIMIT ? OFFSET ?
                """, (rs, n) -> new Row(
                        rs.getLong("id"),
                        rs.getLong("errand_id"),
                        rs.getString("errand_title"),
                        rs.getString("operator_name"),
                        rs.getString("favor"),
                        rs.getLong("amount_cents"),
                        rs.getString("result"),
                        rs.getTimestamp("created_at").toInstant()), limit, offset);
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fund_audit_log WHERE action = 'ARBITRATE'", Long.class);
        return new Page(rows, total == null ? 0 : total);
    }
}
