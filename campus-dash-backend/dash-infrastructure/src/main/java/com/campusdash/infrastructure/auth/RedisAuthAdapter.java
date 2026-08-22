package com.campusdash.infrastructure.auth;

import com.campusdash.domain.auth.ports.AuthPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Redis Session 实现：auth:token:{token} -> userId，TTL 2 小时。
 *
 * token 是纯随机 UUID，不包含任何用户信息——有状态 Session 的特征：
 * 服务端存映射，客户端只持有不可推导的凭证。想吊销（登出/踢人）就是删 key，
 * 这正是 JWT 难做到的地方。
 */
@Component
@ConditionalOnProperty(name = "dash.auth.mode", havingValue = "session", matchIfMissing = true)
public class RedisAuthAdapter implements AuthPort {

    private static final String PREFIX = "auth:token:";

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public RedisAuthAdapter(StringRedisTemplate redis,
                            @Value("${dash.auth.session-ttl-minutes:120}") long ttlMinutes) {
        this.redis = redis;
        this.ttl = Duration.ofMinutes(ttlMinutes);
    }

    @Override
    public String login(long userId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        redis.opsForValue().set(PREFIX + token, String.valueOf(userId), ttl);
        return token;
    }

    @Override
    public Optional<Long> resolve(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String userId = redis.opsForValue().get(PREFIX + token);
        return userId == null ? Optional.empty() : Optional.of(Long.parseLong(userId));
    }

    @Override
    public void logout(String token) {
        redis.delete(PREFIX + token);
    }
}
