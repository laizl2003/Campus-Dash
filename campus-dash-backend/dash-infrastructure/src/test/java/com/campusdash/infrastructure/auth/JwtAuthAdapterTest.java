package com.campusdash.infrastructure.auth;

import com.campusdash.domain.auth.ports.RefreshTokenPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * JWT 刷新令牌的单元测试：只验证与 Redis 的交互契约，不需要真实中间件。
 *
 * 重点是被 GETDEL 取代的 get + delete：那个窗口会让同一张 refresh token
 * 换出两对凭证，轮换语义失效，是"重放必须失败"这条验收标准的实现基础。
 */
class JwtAuthAdapterTest {

    private static final String SECRET = "campus-dash-test-secret-key-at-least-32-bytes!!";
    private static final long TTL_MINUTES = 120;

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private JwtAuthAdapter adapter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        adapter = new JwtAuthAdapter(redis, SECRET, TTL_MINUTES, 10080);
    }

    @Test
    @DisplayName("刷新用 GETDEL 原子取用，并发重放时只有一个线程能拿到值")
    void refresh_uses_atomic_get_and_delete() {
        when(values.getAndDelete("auth:jwt:refresh:r1")).thenReturn("42");

        Optional<RefreshTokenPort.TokenPair> pair = adapter.refresh("r1");

        assertTrue(pair.isPresent(), "GETDEL 拿到 userId 后应签发新的一对");
        verify(values).getAndDelete("auth:jwt:refresh:r1");
        // 两步式 get + delete 会留下重放窗口，这里断言不再出现
        verify(values, never()).get("auth:jwt:refresh:r1");
        verify(redis, never()).delete("auth:jwt:refresh:r1");
        // 轮换：新 refresh token 必须写回 Redis，否则用户刷新一次就永久掉线
        verify(values).set(startsWith("auth:jwt:refresh:"), eq("42"), any(Duration.class));
    }

    @Test
    @DisplayName("已用过或不存在的 refresh token 直接拒绝，且不签发新凭证")
    void refresh_rejects_unknown_or_replayed_token() {
        when(values.getAndDelete(anyString())).thenReturn(null);

        assertTrue(adapter.refresh("used").isEmpty());
        assertTrue(adapter.refresh("never-issued").isEmpty());
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("Redis 里的映射值为空白时按失效处理，不抛异常")
    void refresh_rejects_blank_redis_value() {
        // userId 现在是字符串，"不是数字"不再等于非法；只有空白视为脏数据
        when(values.getAndDelete("auth:jwt:refresh:blank")).thenReturn("   ");

        assertTrue(adapter.refresh("blank").isEmpty());
    }

    @Test
    @DisplayName("空或空白 refresh token 不查 Redis")
    void refresh_rejects_blank_without_touching_redis() {
        assertTrue(adapter.refresh(null).isEmpty());
        assertTrue(adapter.refresh("   ").isEmpty());
        verifyNoInteractions(values);
    }

    @Test
    @DisplayName("被拉黑的 jti 即使签名与有效期都合法也拒绝")
    void resolve_rejects_blacklisted_jti() {
        String token = adapter.login("1001");
        when(redis.hasKey(startsWith("auth:jwt:revoked:"))).thenReturn(true);

        assertTrue(adapter.resolve(token).isEmpty());
    }

    @Test
    @DisplayName("正常签发的 access token 可以解析出 userId")
    void resolve_returns_user_for_valid_token() {
        String token = adapter.login("1001");
        when(redis.hasKey(anyString())).thenReturn(false);

        assertEquals(Optional.of("1001"), adapter.resolve(token));
    }

    @Test
    @DisplayName("登出把 jti 写入黑名单，TTL 取 token 剩余有效期")
    void logout_blacklists_jti_with_remaining_ttl() {
        String token = adapter.login("1001");

        adapter.logout(token);

        verify(values).set(startsWith("auth:jwt:revoked:"), eq("1"),
                argThat((Duration d) -> d.toMillis() > 0 && d.toMillis() <= TTL_MINUTES * 60_000));
    }

    @Test
    @DisplayName("撤销 refresh token 直接删除映射，空白值忽略")
    void revoke_refresh_deletes_mapping() {
        adapter.revokeRefresh("r9");
        verify(redis).delete("auth:jwt:refresh:r9");

        adapter.revokeRefresh("   ");
        adapter.revokeRefresh(null);
        verify(redis, times(1)).delete(anyString());
    }
}
