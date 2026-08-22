package com.campusdash.infrastructure.sharding;

import org.apache.shardingsphere.infra.algorithm.core.config.AlgorithmConfiguration;
import org.apache.shardingsphere.sharding.api.config.ShardingRuleConfiguration;
import org.apache.shardingsphere.sharding.api.config.rule.ShardingTableReferenceRuleConfiguration;
import org.apache.shardingsphere.sharding.api.config.rule.ShardingTableRuleConfiguration;
import org.apache.shardingsphere.sharding.api.config.strategy.sharding.StandardShardingStrategyConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * CampusDash 的 ShardingSphere JDBC 规则工厂。
 */
public final class CampusDashShardingRuleFactory {

    private static final String ALGORITHM_NAME = "campus_dash_mod";
    private static final List<String> TASK_BINDING_TABLES = List.of(
            "errand", "grab_record", "escrow_order", "errand_status_log");
    private static final List<String> USER_TABLES = List.of(
            "wallet_account", "wallet_ledger", "credit_score", "credit_event");

    private CampusDashShardingRuleFactory() {
    }

    public static ShardingRuleConfiguration create(int shardCount) {
        if (shardCount <= 0) {
            throw new IllegalArgumentException("shardCount 必须大于 0");
        }
        ShardingRuleConfiguration result = new ShardingRuleConfiguration();
        List<ShardingTableRuleConfiguration> tables = new ArrayList<>();
        TASK_BINDING_TABLES.forEach(table -> tables.add(createTableRule(table, "campus_id", shardCount)));
        USER_TABLES.forEach(table -> tables.add(createTableRule(table, "user_id", shardCount)));
        result.setTables(tables);
        result.setBindingTableGroups(List.of(new ShardingTableReferenceRuleConfiguration(
                "task_binding", String.join(",", TASK_BINDING_TABLES))));
        result.setShardingAlgorithms(Map.of(ALGORITHM_NAME, createAlgorithm(shardCount)));
        return result;
    }

    private static ShardingTableRuleConfiguration createTableRule(String tableName,
                                                                  String shardingColumn,
                                                                  int shardCount) {
        ShardingTableRuleConfiguration result = new ShardingTableRuleConfiguration(
                tableName, "campus_dash_${0.." + (shardCount - 1) + "}." + tableName);
        result.setDatabaseShardingStrategy(new StandardShardingStrategyConfiguration(shardingColumn, ALGORITHM_NAME));
        return result;
    }

    private static AlgorithmConfiguration createAlgorithm(int shardCount) {
        Properties props = new Properties();
        props.setProperty("strategy", "STANDARD");
        props.setProperty("algorithmClassName", CampusDashModuloShardingAlgorithm.class.getName());
        props.setProperty("shard-count", String.valueOf(shardCount));
        return new AlgorithmConfiguration("CLASS_BASED", props);
    }
}
