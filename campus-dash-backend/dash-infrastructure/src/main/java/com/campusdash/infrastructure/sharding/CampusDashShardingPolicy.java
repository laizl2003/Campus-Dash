package com.campusdash.infrastructure.sharding;

import java.util.Set;

/**
 * P6 分片路由规则的可执行规格。
 */
public class CampusDashShardingPolicy {

    private static final Set<String> TASK_BINDING_TABLES = Set.of(
            "errand", "grab_record", "escrow_order", "errand_status_log");
    private static final Set<String> USER_TABLES = Set.of(
            "wallet_account", "wallet_ledger", "credit_score", "credit_event");

    private final int shardCount;

    public CampusDashShardingPolicy(int shardCount) {
        if (shardCount <= 0) {
            throw new IllegalArgumentException("shardCount 必须大于 0");
        }
        this.shardCount = shardCount;
    }

    public String routeTaskTable(String table, long campusId) {
        if (!TASK_BINDING_TABLES.contains(table)) {
            throw new IllegalArgumentException("不是任务共片表: " + table);
        }
        return routeTaskShard(campusId);
    }

    public String routeTaskShard(long campusId) {
        return "campus_" + mod(campusId);
    }

    public String routeUserTable(String table, long userId) {
        if (!USER_TABLES.contains(table)) {
            throw new IllegalArgumentException("不是用户维度表: " + table);
        }
        return "user_" + mod(userId);
    }

    private int mod(long value) {
        return Math.floorMod(value, shardCount);
    }
}
