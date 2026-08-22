package com.campusdash.infrastructure.cache;

import com.campusdash.domain.grab.ports.CandidateQueuePort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

/**
 * 候选队列的 Redis ZSET 实现。
 * score 越小越优先：score = 抢单时间戳 - 信用分加权，
 * 让高信用用户在同等手速下更容易接到流转的单，但加权有上限，避免高信用用户垄断。
 */
@Component
public class RedisCandidateQueueAdapter implements CandidateQueuePort {

    private final StringRedisTemplate redis;

    public RedisCandidateQueueAdapter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void offer(long errandId, long runnerId, double score) {
        redis.opsForZSet().add(key(errandId), String.valueOf(runnerId), score);
    }

    @Override
    public Optional<Long> pollBest(long errandId) {
        Set<String> popped = redis.opsForZSet().range(key(errandId), 0, 0);
        if (popped == null || popped.isEmpty()) {
            return Optional.empty();
        }
        String runner = popped.iterator().next();
        redis.opsForZSet().remove(key(errandId), runner);
        return Optional.of(Long.parseLong(runner));
    }

    @Override
    public long size(long errandId) {
        Long n = redis.opsForZSet().zCard(key(errandId));
        return n == null ? 0L : n;
    }

    private String key(long errandId) {
        return "errand:candidates:{" + errandId + "}";
    }
}
