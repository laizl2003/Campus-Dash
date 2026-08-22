package com.campusdash.presentation.auth;

import com.campusdash.domain.auth.ports.RefreshTokenPort;
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
