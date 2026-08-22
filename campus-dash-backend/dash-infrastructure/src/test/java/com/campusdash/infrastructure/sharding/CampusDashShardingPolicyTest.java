package com.campusdash.infrastructure.sharding;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CampusDashShardingPolicyTest {

    @Test
    void routesErrandBindingTablesBySameCampusShard() {
        CampusDashShardingPolicy policy = new CampusDashShardingPolicy(4);

        String shard = policy.routeTaskShard(7L);

        assertEquals(shard, policy.routeTaskTable("errand", 7L));
        assertEquals(shard, policy.routeTaskTable("grab_record", 7L));
        assertEquals(shard, policy.routeTaskTable("escrow_order", 7L));
        assertEquals(shard, policy.routeTaskTable("errand_status_log", 7L));
    }

    @Test
    void routesUserTablesByUserShard() {
        CampusDashShardingPolicy policy = new CampusDashShardingPolicy(4);

        assertEquals("user_2", policy.routeUserTable("wallet_account", 10L));
        assertEquals("user_2", policy.routeUserTable("wallet_ledger", 10L));
        assertEquals("user_2", policy.routeUserTable("credit_score", 10L));
        assertEquals("user_2", policy.routeUserTable("credit_event", 10L));
    }

    @Test
    void productionSqlDoesNotQueryTaskBindingTablesByErrandIdOnly() throws Exception {
        String infraSql;
        try (var paths = Files.walk(Path.of("src/main/java"))) {
            infraSql = paths.filter(p -> p.toString().endsWith(".java"))
                    .map(path -> {
                        try {
                            return Files.readString(path);
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .collect(Collectors.joining("\n"));
        }

        assertFalse(infraSql.contains("FROM grab_record WHERE errand_id = ?"),
                "grab_record 按 errand_id 查询必须同时带 campus_id，否则分片后广播");
        assertFalse(infraSql.contains("FROM escrow_order WHERE errand_id = ?"),
                "escrow_order 按 errand_id 查询必须同时带 campus_id，否则分片后广播");
        assertFalse(infraSql.contains("UPDATE escrow_order SET status = ? WHERE errand_id = ?"),
                "escrow_order 状态 CAS 必须同时带 campus_id，否则分片后广播");
        assertFalse(infraSql.contains("FROM errand_status_log\n                 WHERE errand_id = ?"),
                "errand_status_log 时间线查询必须同时带 campus_id，否则分片后广播");
    }
}
