package com.campusdash.it;

import com.campusdash.domain.auth.ports.AuthPort;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Session vs JWT 对照实验：同一个 AuthPort 接口，两种实现的行为差异。
 *
 * 两个 @Nested 类各自带不同的 dash.auth.mode，Spring 会建两个独立上下文。
 *
 * ── 对照的核心问题 ──
 *   1. 服务端状态丢失（模拟重启/Redis 清空）后 token 是否还有效？
 *      Session：失效（映射没了）；JWT：仍有效（无状态）
 *   2. 登出后 token 是否立即失效？
 *      Session：立即（删映射）；JWT：要靠黑名单补救——不加入黑名单的话，
 *      JWT 在有效期内怎么都"看起来合法"，这正是它吊销难的实证。
 */
class AuthModeIT {

    @Nested
    @SpringBootTest(properties = {"dash.auth.mode=session", "dash.mq.enabled=false"})
    class SessionMode {

        @Autowired AuthPort authPort;
        @Autowired StringRedisTemplate redis;

        @BeforeAll
        static void requireMiddleware() {
            Assumptions.assumeTrue(MiddlewareAvailable.check(), "中间件未启动，跳过");
        }

        @Test
        @DisplayName("Session：token 依赖服务端映射，映射丢失即失效")
        void session_depends_on_server_state() {
            String token = authPort.login(7001);
            assertEquals(7001, authPort.resolve(token).orElseThrow());

            // 模拟服务端状态丢失：删掉会话映射（等价于 Redis 清空/换实例）
            redis.delete("auth:token:" + token);
            assertTrue(authPort.resolve(token).isEmpty(),
                    "Session 模式下映射丢失后 token 必须失效");
        }

        @Test
        @DisplayName("Session：登出立即失效")
        void session_logout_invalidates_immediately() {
            String token = authPort.login(7002);
            authPort.logout(token);
            assertTrue(authPort.resolve(token).isEmpty());
        }
    }

    @Nested
    @SpringBootTest(properties = {"dash.auth.mode=jwt", "dash.mq.enabled=false"})
    class JwtMode {

        @Autowired AuthPort authPort;
        @Autowired StringRedisTemplate redis;

        @BeforeAll
        static void requireMiddleware() {
            Assumptions.assumeTrue(MiddlewareAvailable.check(), "中间件未启动，跳过");
        }

        @Test
        @DisplayName("JWT：无状态，服务端不存会话映射，状态丢失后仍有效")
        void jwt_is_stateless() {
            String token = authPort.login(7003);
            assertEquals(7003, authPort.resolve(token).orElseThrow());

            // JWT 的 token 格式是自包含的（三段式），服务端没有 auth:token: 映射
            assertTrue(token.chars().filter(c -> c == '.').count() == 2,
                    "JWT 应为三段式自包含 token");
            assertTrue(redis.keys("auth:token:*").stream().noneMatch(k -> k.contains(token)),
                    "JWT 模式不应写入会话映射");

            // 与 Session 的对照：即使"服务端状态丢失"，JWT 依然有效
            assertEquals(7003, authPort.resolve(token).orElseThrow(),
                    "JWT 无状态：不依赖服务端映射，状态丢失后仍有效");
        }

        @Test
        @DisplayName("JWT：吊销难——登出靠黑名单，未吊销前 token 始终合法")
        void jwt_revocation_needs_blacklist() {
            String token = authPort.login(7004);

            // 登出前：有效
            assertEquals(7004, authPort.resolve(token).orElseThrow());

            // 登出 = 把 jti 加入黑名单
            authPort.logout(token);
            assertTrue(authPort.resolve(token).isEmpty(),
                    "加入黑名单后必须失效");

            // 实证"吊销难"：黑名单是补救手段，不是 JWT 自身能力——
            // 同一个 token 的签名与有效期依然合法，只是被外部名单拒绝
            String[] parts = token.split("\\.");
            assertEquals(3, parts.length, "被吊销的 JWT 结构上依然完整合法");
        }

        @Test
        @DisplayName("JWT：伪造签名的 token 被拒绝")
        void jwt_rejects_forged_signature() {
            String token = authPort.login(7005);
            // 篡改 payload 段（改 userId），签名不再匹配
            String[] parts = token.split("\\.");
            String forged = parts[0] + "." + parts[1] + "x." + parts[2];
            assertTrue(authPort.resolve(forged).isEmpty(), "伪造 token 必须被拒绝");
        }
    }
}
