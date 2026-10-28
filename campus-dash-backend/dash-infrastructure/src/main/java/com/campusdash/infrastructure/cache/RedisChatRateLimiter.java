package com.campusdash.infrastructure.cache;

import com.campusdash.domain.chat.ports.ChatRateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/** Redis 固定窗口：每人每秒最多 N 条。 */
@Component
public class RedisChatRateLimiter implements ChatRateLimiter {

    private final StringRedisTemplate redis;
    private final int perSecond;

    public RedisChatRateLimiter(StringRedisTemplate redis,
                                @Value("${dash.chat.rate-per-second:3}") int perSecond) {
        this.redis = redis;
        this.perSecond = perSecond;
    }

    @Override
    public boolean allow(String userId) {
        String key = "chat:rl:" + userId + ":" + Instant.now().getEpochSecond();
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, Duration.ofSeconds(2));
        }
        return count != null && count <= perSecond;
    }
}
