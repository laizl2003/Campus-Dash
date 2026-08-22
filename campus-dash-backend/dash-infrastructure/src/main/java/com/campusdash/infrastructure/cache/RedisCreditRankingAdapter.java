package com.campusdash.infrastructure.cache;

import com.campusdash.domain.credit.ports.CreditRankingPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * ZSET 排行榜实现。key 按校区隔离：credit:rank:{campusId}。
 *
 * Redis 操作失败不上抛：排行榜是展示层能力，挂了不影响信用分本身
 * （分数事实在 MySQL）。每日校准 job 会重建 ZSET，短暂不一致可自愈。
 */
@Component
public class RedisCreditRankingAdapter implements CreditRankingPort {

    private static final String KEY_PREFIX = "credit:rank:";

    private final StringRedisTemplate redis;

    public RedisCreditRankingAdapter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void update(long campusId, long userId, int score) {
        try {
            redis.opsForZSet().add(KEY_PREFIX + campusId, String.valueOf(userId), score);
        } catch (RuntimeException ignored) {
            // 排行榜降级不影响业务
        }
    }

    @Override
    public List<Entry> top(long campusId, int limit) {
        List<Entry> result = new ArrayList<>();
        try {
            Set<ZSetOperations.TypedTuple<String>> tuples =
                    redis.opsForZSet().reverseRangeWithScores(KEY_PREFIX + campusId, 0, limit - 1L);
            if (tuples != null) {
                for (var t : tuples) {
                    result.add(new Entry(Long.parseLong(t.getValue()), t.getScore().intValue()));
                }
            }
        } catch (RuntimeException ignored) {
            // 降级返回空榜
        }
        return result;
    }
}
