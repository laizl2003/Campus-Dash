package com.campusdash.infrastructure.sharding;

import org.apache.shardingsphere.sharding.api.sharding.standard.PreciseShardingValue;
import org.apache.shardingsphere.sharding.api.sharding.standard.RangeShardingValue;
import org.apache.shardingsphere.sharding.api.sharding.standard.StandardShardingAlgorithm;

import java.util.Collection;
import java.util.Comparator;
import java.util.Properties;

/**
 * ShardingSphere class-based 标准分片算法。
 *
 * 表结构仍保留无后缀表名；这里按分片键取模路由到 campus_dash_0..N 数据源。
 */
public class CampusDashModuloShardingAlgorithm implements StandardShardingAlgorithm<Comparable<?>> {

    private int shardCount;

    @Override
    public void init(Properties props) {
        String configured = props == null ? null : props.getProperty("shard-count");
        shardCount = configured == null || configured.isBlank() ? 0 : Integer.parseInt(configured);
        if (shardCount < 0) {
            throw new IllegalArgumentException("shard-count 不能为负数");
        }
    }

    @Override
    public String doSharding(Collection<String> availableTargetNames,
                             PreciseShardingValue<Comparable<?>> shardingValue) {
        if (availableTargetNames == null || availableTargetNames.isEmpty()) {
            throw new IllegalArgumentException("可用分片不能为空");
        }
        int count = shardCount == 0 ? availableTargetNames.size() : shardCount;
        int shard = Math.floorMod(toLong(shardingValue.getValue()), count);
        return availableTargetNames.stream()
                .filter(target -> target.endsWith("_" + shard))
                .sorted(Comparator.naturalOrder())
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("找不到目标分片: " + shard));
    }

    @Override
    public Collection<String> doSharding(Collection<String> availableTargetNames,
                                         RangeShardingValue<Comparable<?>> shardingValue) {
        return availableTargetNames;
    }

    @Override
    public String getType() {
        return "CAMPUS_DASH_MOD";
    }

    private long toLong(Comparable<?> value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(value.toString());
    }
}
