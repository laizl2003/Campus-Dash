package com.campusdash.infrastructure.auth;

import com.campusdash.domain.auth.ports.RefreshTokenPort;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * JWT 实现（dash.auth.mode=jwt 时装配），与 Session 构成对照组。
 *
 * ── 与 Session 的核心差异（教程对比点）──
 *   1. 无状态：token 自带 userId 与有效期，服务端不存会话映射。
 *      服务重启后 token 依然有效（Session 则依赖 Redis 里的映射）。
 *   2. 吊销难：JWT 签发后无法"作废"，只能靠黑名单补救——
 *      本实现用 Redis 存 auth:jwt:revoked:{jti}，TTL 设为 token 剩余有效期。
 *      这等于承认"纯无状态"在需要吊销的场景里是伪命题。
 *   3. 续期：Session 每次访问自动延长 TTL；JWT 要显式换新 token
 *      （本实现简化为固定有效期，刷新令牌机制留作教程演进点）。
 *
 * ── 秘钥管理 ──
 * 秘钥从配置读（生产应从环境变量/密钥服务注入，绝不进代码库）。
 * 配置里的默认值仅供本地演示。
 */
@Component
@ConditionalOnProperty(name = "dash.auth.mode", havingValue = "jwt")
public class JwtAuthAdapter implements RefreshTokenPort {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthAdapter.class);
    private static final String REVOKED_PREFIX = "auth:jwt:revoked:";
    private static final String REFRESH_PREFIX = "auth:jwt:refresh:";

    private final SecretKey key;
    private final StringRedisTemplate redis;
    private final long ttlMinutes;
    private final long refreshTtlMinutes;

    public JwtAuthAdapter(StringRedisTemplate redis,
                          @Value("${dash.auth.jwt-secret:campus-dash-local-demo-secret-key-min-32-bytes!!}") String secret,
                          @Value("${dash.auth.session-ttl-minutes:120}") long ttlMinutes,
                          @Value("${dash.auth.refresh-ttl-minutes:10080}") long refreshTtlMinutes) {
        this.redis = redis;
        this.ttlMinutes = ttlMinutes;
        this.refreshTtlMinutes = refreshTtlMinutes;
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String login(long userId) {
        return issueAccessToken(userId);
    }

    @Override
    public TokenPair loginWithRefresh(long userId) {
        return new TokenPair(issueAccessToken(userId), issueRefreshToken(userId));
    }

    @Override
    public Optional<TokenPair> refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return Optional.empty();
        }
        String key = REFRESH_PREFIX + refreshToken;
        String userIdText = redis.opsForValue().get(key);
        if (userIdText == null) {
            return Optional.empty();
        }
        redis.delete(key);
        long userId = Long.parseLong(userIdText);
        return Optional.of(new TokenPair(issueAccessToken(userId), issueRefreshToken(userId)));
    }

    @Override
    public void revokeRefresh(String refreshToken) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            redis.delete(REFRESH_PREFIX + refreshToken);
        }
    }

    private String issueAccessToken(long userId) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .id(UUID.randomUUID().toString())          // jti：吊销黑名单的键
                .issuedAt(now)
                .expiration(new Date(now.getTime() + ttlMinutes * 60_000))
                .signWith(key)
                .compact();
    }

    private String issueRefreshToken(long userId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        redis.opsForValue().set(REFRESH_PREFIX + token, String.valueOf(userId),
                Duration.ofMinutes(refreshTtlMinutes));
        return token;
    }

    @Override
    public Optional<Long> resolve(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Jws<Claims> jws = Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
            Claims claims = jws.getPayload();
            // 黑名单检查：被吊销的 jti 即使签名与有效期都合法也拒绝
            String jti = claims.getId();
            if (jti != null && Boolean.TRUE.equals(redis.hasKey(REVOKED_PREFIX + jti))) {
                return Optional.empty();
            }
            return Optional.of(Long.parseLong(claims.getSubject()));
        } catch (JwtException | IllegalArgumentException e) {
            // 签名伪造 / 过期 / 格式错误：一律视为无效
            return Optional.empty();
        }
    }

    @Override
    public void logout(String token) {
        try {
            Jws<Claims> jws = Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
            Claims claims = jws.getPayload();
            String jti = claims.getId();
            long remainMs = claims.getExpiration().getTime() - System.currentTimeMillis();
            if (jti != null && remainMs > 0) {
                // 黑名单 TTL = token 剩余有效期：token 自然过期后黑名单自动清理
                redis.opsForValue().set(REVOKED_PREFIX + jti, "1", Duration.ofMillis(remainMs));
            }
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("登出的 token 已无效，忽略");
        }
    }
}
