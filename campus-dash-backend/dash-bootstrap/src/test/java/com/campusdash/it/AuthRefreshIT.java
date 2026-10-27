package com.campusdash.it;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P6：JWT 刷新令牌。
 *
 * access token 仍是短生命周期 JWT；refresh token 是服务端可吊销、可轮换的随机凭证。
 * 这里验证三条安全语义：
 *   1. 刷新成功后旧 refresh token 立即失效，不能反复换新 access token；
 *   2. 同一张 refresh token 并发提交只有一次成功（GETDEL 原子取用）；
 *   3. 登出撤销 refresh token，之后不能再换出新 access。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"dash.auth.mode=jwt", "dash.mq.enabled=false", "dash.auth.refresh-ttl-minutes=120"})
class AuthRefreshIT {

    @Autowired TestRestTemplate rest;

    @BeforeAll
    static void requireMiddleware() {
        Assumptions.assumeTrue(MiddlewareAvailable.check(), "中间件未启动，跳过");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(Map<?, ?> body) {
        assertEquals("OK", body.get("code"), "响应应成功，实际: " + body);
        return (Map<String, Object>) body.get("data");
    }

    @Test
    @DisplayName("JWT refresh token 刷新后轮换，旧 refresh token 立即失效")
    void refresh_token_rotates_and_old_token_is_invalidated() {
        Map<String, Object> login = data(rest.postForEntity("/api/auth/login",
                Map.of("userId", "7101"), Map.class).getBody());

        String accessToken = String.valueOf(login.get("accessToken"));
        String refreshToken = String.valueOf(login.get("refreshToken"));
        assertNotEquals("null", accessToken, "登录响应必须返回 accessToken");
        assertNotEquals("null", refreshToken, "登录响应必须返回 refreshToken");
        assertEquals(accessToken, login.get("token"), "兼容旧前端：token 仍等于 accessToken");

        Map<String, Object> refreshed = data(rest.postForEntity("/api/auth/refresh",
                Map.of("refreshToken", refreshToken), Map.class).getBody());
        String nextAccessToken = String.valueOf(refreshed.get("accessToken"));
        String nextRefreshToken = String.valueOf(refreshed.get("refreshToken"));
        assertNotEquals(accessToken, nextAccessToken, "刷新后应签发新的 access token");
        assertNotEquals(refreshToken, nextRefreshToken, "refresh token 必须轮换");

        Map<?, ?> replay = rest.postForEntity("/api/auth/refresh",
                Map.of("refreshToken", refreshToken), Map.class).getBody();
        assertEquals("UNAUTHORIZED", replay.get("code"), "旧 refresh token 复用必须被拒绝");
    }

    @Test
    @DisplayName("登出撤销 refresh token，之后刷新被拒绝")
    @SuppressWarnings("unchecked")
    void logout_revokes_refresh_token() {
        Map<String, Object> login = data(rest.postForEntity("/api/auth/login",
                Map.of("userId", "7102"), Map.class).getBody());
        String accessToken = String.valueOf(login.get("accessToken"));
        String refreshToken = String.valueOf(login.get("refreshToken"));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);
        ResponseEntity<Map> logout = rest.postForEntity("/api/auth/logout",
                new HttpEntity<>(Map.of("refreshToken", refreshToken), headers), Map.class);
        assertEquals("OK", logout.getBody().get("code"), "登出应成功: " + logout.getBody());

        Map<?, ?> replay = rest.postForEntity("/api/auth/refresh",
                Map.of("refreshToken", refreshToken), Map.class).getBody();
        assertEquals("UNAUTHORIZED", replay.get("code"), "登出后 refresh token 必须立即失效");
    }

    @Test
    @DisplayName("同一 refresh token 并发提交只有一次成功")
    void concurrent_refresh_on_same_token_has_single_winner() throws Exception {
        Map<String, Object> login = data(rest.postForEntity("/api/auth/login",
                Map.of("userId", "7103"), Map.class).getBody());
        String refreshToken = String.valueOf(login.get("refreshToken"));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    Map<?, ?> body = rest.postForEntity("/api/auth/refresh",
                            Map.of("refreshToken", refreshToken), Map.class).getBody();
                    return String.valueOf(body.get("code"));
                }));
            }
            start.countDown();
            int ok = 0;
            for (Future<String> future : futures) {
                if ("OK".equals(future.get(10, TimeUnit.SECONDS))) {
                    ok++;
                }
            }
            assertEquals(1, ok, "并发重放同一 refresh token 只能成功一次（GETDEL 原子取用）");
        } finally {
            pool.shutdownNow();
        }
    }
}
