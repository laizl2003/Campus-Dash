package com.campusdash.it;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P6：JWT 刷新令牌。
 *
 * access token 仍是短生命周期 JWT；refresh token 是服务端可吊销、可轮换的随机凭证。
 * 这里验证最关键的安全语义：刷新成功后旧 refresh token 必须失效，不能反复换新 access token。
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
                Map.of("userId", 7101L), Map.class).getBody());

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
}
