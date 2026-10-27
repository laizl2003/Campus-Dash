package com.campusdash.infrastructure.cache;

import com.campusdash.domain.auth.ports.ForceLogoutPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 强制下线标记：auth:forced:{userId}，TTL 12 小时。
 * AuthInterceptor 与 WS 握手都会检查；用户重新登录成功时清除。
 */
@Component
public class RedisForceLogoutAdapter implements ForceLogoutPort {

    private static final String KEY_PREFIX = "auth:forced:";
    private static final Duration TTL = Duration.ofHours(12);

    private final StringRedisTemplate redis;

    public RedisForceLogoutAdapter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void force(String userId) {
        redis.opsForValue().set(KEY_PREFIX + userId, String.valueOf(System.currentTimeMillis()), TTL);
    }

    @Override
    public boolean isForced(String userId) {
        Boolean exists = redis.hasKey(KEY_PREFIX + userId);
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public void clear(String userId) {
        redis.delete(KEY_PREFIX + userId);
    }
}
