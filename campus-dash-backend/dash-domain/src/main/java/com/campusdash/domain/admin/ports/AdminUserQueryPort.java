package com.campusdash.domain.admin.ports;

import java.time.Instant;
import java.util.List;

/** 管理端用户列表查询端口 */
public interface AdminUserQueryPort {

    Page page(int offset, int limit);

    record Row(String userId, String username, Instant lastLoginAt, long balanceCents,
               boolean loginStatus, Instant lastSeenAt, boolean enabled) {
    }

    record Page(List<Row> items, long total) {
    }
}
