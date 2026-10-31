package com.campusdash.domain.admin.ports;

import java.time.Instant;
import java.util.List;

/** 管理端用户列表查询端口 */
public interface AdminUserQueryPort {

    Page page(int offset, int limit);

    /**
     * 按在线状态优先排序后分页。
     *
     * 之前是"取全量 10000 行再排序"，用户数涨到 10000 之后最老的一条被 LIMIT 截掉
     * （laizl 就是这么从管理端消失的）。这里把在线集合下推到 SQL 排序，
     * 既不再丢人，也不用每页都搬一万行到内存。
     *
     * @param onlineUserIds 当前在线用户 id，可为空
     */
    default Page page(int offset, int limit, List<String> onlineUserIds) {
        return page(offset, limit);
    }

    record Row(String userId, String username, Instant lastLoginAt, long balanceCents,
               boolean loginStatus, Instant lastSeenAt, boolean enabled, boolean messageStatus) {
    }

    record Page(List<Row> items, long total) {
    }
}
