package com.campusdash.presentation.auth;

import com.campusdash.domain.auth.ports.RefreshTokenPort;
import com.campusdash.domain.auth.ports.AuthPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class AuthControllerTest {

    @Test
    @DisplayName("JWT refresh token 刷新后轮换，旧 token 复用被拒绝")
    void refresh_rotates_token_and_rejects_replay() {
        FakeRefreshAuthPort authPort = new FakeRefreshAuthPort();
        AuthController controller = new AuthController(authPort);

        var login = controller.login(new AuthController.LoginRequest(7001L)).data();
        assertEquals(login.get("token"), login.get("accessToken"));
        String refreshToken = String.valueOf(login.get("refreshToken"));

        var refreshed = controller.refresh(new AuthController.RefreshRequest(refreshToken));
        assertEquals("OK", refreshed.code());
        String nextRefreshToken = String.valueOf(refreshed.data().get("refreshToken"));
        assertNotEquals(refreshToken, nextRefreshToken);

        var replay = controller.refresh(new AuthController.RefreshRequest(refreshToken));
        assertEquals("UNAUTHORIZED", replay.code());
    }

    @Test
    @DisplayName("演示登录拒绝空值、零和系统负数身份，不发出 JWT 或刷新令牌")
    void login_rejects_non_positive_or_missing_identity_before_issuing_tokens() {
        FakeRefreshAuthPort auth = new FakeRefreshAuthPort();
        AuthController controller = new AuthController(auth);
        for (Long userId : new Long[] { null, 0L, -1L, -2L, Long.MIN_VALUE }) {
            assertEquals("UNAUTHORIZED", controller.login(new AuthController.LoginRequest(userId)).code());
        }
        assertEquals("UNAUTHORIZED", controller.login(null).code());
        assertEquals(0, auth.accessSeq);
        assertEquals(0, auth.refreshSeq);
    }

    @Test
    @DisplayName("Session 模式同样拒绝系统身份，仍允许正常演示用户")
    void session_login_rejects_system_identity_but_accepts_positive_users() {
        int[] issued = { 0 };
        AuthPort auth = new AuthPort() {
            @Override public String login(long userId) { issued[0]++; return "session-" + userId; }
            @Override public Optional<Long> resolve(String token) { return Optional.empty(); }
            @Override public void logout(String token) {}
        };
        AuthController controller = new AuthController(auth);
        assertEquals("UNAUTHORIZED", controller.login(new AuthController.LoginRequest(-1L)).code());
        assertEquals(0, issued[0]);
        var result = controller.login(new AuthController.LoginRequest(2001L));
        assertEquals("OK", result.code());
        assertEquals("session-2001", result.data().get("token"));
        assertEquals(1, issued[0]);
    }

    @Test
    @DisplayName("登出撤销 refresh token，之后无法再用它换新 access")
    void logout_revokes_refresh_token() {
        FakeRefreshAuthPort auth = new FakeRefreshAuthPort();
        AuthController controller = new AuthController(auth);
        var login = controller.login(new AuthController.LoginRequest(7201L)).data();
        String access = String.valueOf(login.get("accessToken"));
        String refresh = String.valueOf(login.get("refreshToken"));

        assertEquals("OK", controller.logout("Bearer " + access,
                new AuthController.LogoutRequest(refresh)).code());
        assertEquals("UNAUTHORIZED",
                controller.refresh(new AuthController.RefreshRequest(refresh)).code(),
                "登出后 refresh token 必须立即失效");
    }

    @Test
    @DisplayName("登出没有 body 时退化为只撤销 access，不抛异常")
    void logout_without_body_is_still_valid() {
        FakeRefreshAuthPort auth = new FakeRefreshAuthPort();
        AuthController controller = new AuthController(auth);
        var login = controller.login(new AuthController.LoginRequest(7202L)).data();
        String access = String.valueOf(login.get("accessToken"));

        assertEquals("OK", controller.logout(null, null).code());
        assertEquals("OK", controller.logout("Bearer " + access, null).code());
        assertEquals("OK", controller.logout(null, new AuthController.LogoutRequest("   ")).code());
    }

    @Test
    @DisplayName("Session 模式登出不触碰 refresh（端口不支持），仍返回成功")
    void session_logout_ignores_refresh_body() {
        AuthPort auth = new AuthPort() {
            @Override public String login(long userId) { return "session-" + userId; }
            @Override public Optional<Long> resolve(String token) { return Optional.empty(); }
            @Override public void logout(String token) {}
        };
        AuthController controller = new AuthController(auth);
        assertEquals("OK", controller.logout("Bearer session-1",
                new AuthController.LogoutRequest("whatever")).code());
    }

    private static final class FakeRefreshAuthPort implements RefreshTokenPort {
        private final Map<String, Long> refreshTokens = new HashMap<>();
        private int accessSeq;
        private int refreshSeq;

        @Override
        public TokenPair loginWithRefresh(long userId) {
            return new TokenPair(issueAccess(userId), issueRefresh(userId));
        }

        @Override
        public Optional<TokenPair> refresh(String refreshToken) {
            Long userId = refreshTokens.remove(refreshToken);
            if (userId == null) {
                return Optional.empty();
            }
            return Optional.of(new TokenPair(issueAccess(userId), issueRefresh(userId)));
        }

        private String issueAccess(long userId) {
            return "access-" + userId + "-" + (++accessSeq);
        }

        private String issueRefresh(long userId) {
            String token = "refresh-" + userId + "-" + (++refreshSeq);
            refreshTokens.put(token, userId);
            return token;
        }

        @Override public String login(long userId) { return issueAccess(userId); }
        @Override public Optional<Long> resolve(String token) { return Optional.empty(); }
        @Override public void logout(String token) {}
        @Override public void revokeRefresh(String refreshToken) { refreshTokens.remove(refreshToken); }
    }
}
