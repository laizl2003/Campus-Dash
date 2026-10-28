package com.campusdash.infrastructure.auth;

import com.campusdash.domain.auth.ports.AuthPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Redis Session 实现。
 *
 * 凭证：
 *   auth:token:{token}       -> userId，TTL = session-ttl
 *   auth:sessions:{userId}   ZSET token -> 该 token 的过期时间戳
 *   auth:online              ZSET userId -> 该用户最晚一个 token 的过期时间戳
 *
 * 用 ZSET 而不是 Set：Set 不支持 member 级 TTL，无法让多个 token 各自过期；
 * ZSET 用 score 存过期时间，读取/统计时按 score 裁剪。
 */
@Component
@ConditionalOnProperty(name = "dash.auth.mode", havingValue = "session", matchIfMissing = true)
public class RedisAuthAdapter implements AuthPort {

    private static final String PREFIX = "auth:token:";
    private static final String SESSIONS = "auth:sessions:";
    private static final String ONLINE = "auth:online";

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public RedisAuthAdapter(StringRedisTemplate redis,
                            @Value("${dash.auth.session-ttl-minutes:120}") long ttlMinutes) {
        this.redis = redis;
        this.ttl = Duration.ofMinutes(ttlMinutes);
    }

    @Override
    public String login(String userId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        Instant expireAt = Instant.now().plus(ttl);
        redis.opsForValue().set(PREFIX + token, userId, ttl);
        redis.opsForZSet().add(SESSIONS + userId, token, expireAt.toEpochMilli());
        redis.opsForZSet().add(ONLINE, userId, expireAt.toEpochMilli());
        // key 只是容器，真正过期由 score 裁剪；这里延长容器寿命避免活跃用户 key 先过期
        redis.expire(SESSIONS + userId, ttl.plusHours(1));
        return token;
    }

    @Override
    public Optional<String> resolve(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        String userId = redis.opsForValue().get(PREFIX + token);
        return userId == null ? Optional.empty() : Optional.of(userId);
    }

    @Override
    public void touch(String token) {
        if (token == null || token.isBlank()) return;
        String userId = redis.opsForValue().get(PREFIX + token);
        if (userId == null) return;

        Instant expireAt = Instant.now().plus(ttl);
        redis.expire(PREFIX + token, ttl);
        redis.opsForZSet().add(SESSIONS + userId, token, expireAt.toEpochMilli());
        redis.opsForZSet().add(ONLINE, userId, expireAt.toEpochMilli());
        redis.expire(SESSIONS + userId, ttl.plusHours(1));

        // 顺手裁掉该用户已过期的 token，避免 ZSET 无限增长
        redis.opsForZSet().removeRangeByScore(SESSIONS + userId, Double.NEGATIVE_INFINITY,
                System.currentTimeMillis());
    }

    @Override
    public void logout(String token) {
        if (token == null || token.isBlank()) return;
        String userId = redis.opsForValue().get(PREFIX + token);
        redis.delete(PREFIX + token);
        if (userId == null) return;

        redis.opsForZSet().remove(SESSIONS + userId, token);
        redis.opsForZSet().removeRangeByScore(SESSIONS + userId, Double.NEGATIVE_INFINITY,
                System.currentTimeMillis());
        Long left = redis.opsForZSet().zCard(SESSIONS + userId);
        if (left == null || left == 0) {
            redis.opsForZSet().remove(ONLINE, userId);
            redis.delete(SESSIONS + userId);
        }
    }

    @Override
    public void revokeAllSessions(String userId) {
        Set<String> tokens = redis.opsForZSet().range(SESSIONS + userId, 0, -1);
        if (tokens != null) {
            for (String token : tokens) {
                redis.delete(PREFIX + token);
            }
        }
        redis.delete(SESSIONS + userId);
        redis.opsForZSet().remove(ONLINE, userId);
    }

    @Override
    public int onlineCount() {
        long now = System.currentTimeMillis();
        redis.opsForZSet().removeRangeByScore(ONLINE, Double.NEGATIVE_INFINITY, now);
        Long count = redis.opsForZSet().zCard(ONLINE);
        return count == null ? 0 : count.intValue();
    }

    @Override
    public boolean isOnline(String userId) {
        Double score = redis.opsForZSet().score(ONLINE, userId);
        if (score == null) return false;
        if (score <= System.currentTimeMillis()) {
            redis.opsForZSet().remove(ONLINE, userId);
            return false;
        }
        return true;
    }
}
