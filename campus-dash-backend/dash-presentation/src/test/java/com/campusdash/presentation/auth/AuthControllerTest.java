package com.campusdash.presentation.auth;

import com.campusdash.application.usecase.LoginUserUseCase;
import com.campusdash.domain.auth.model.UserAccount;
import com.campusdash.domain.auth.ports.AuthPort;
import com.campusdash.domain.auth.ports.CaptchaPort;
import com.campusdash.domain.auth.ports.PasswordHasher;
import com.campusdash.domain.auth.ports.RefreshTokenPort;
import com.campusdash.domain.auth.ports.UserRepository;
import com.campusdash.shared.BizException;
import com.campusdash.shared.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;


import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** 登录控制器：演示后门、用户名密码正门、刷新轮换、登出撤销 */
class AuthControllerTest {



    @Test
    @DisplayName("演示登录：userId 直传，拒绝系统负数身份")
    void demo_login_rejects_system_identity() {
        AuthController controller = controller(new FakeAuth(), true);
        assertEquals("OK", controller.login(new AuthController.LoginRequest(
                null, null, null, null, "1001"), null).code());
        for (String bad : new String[] { null, "", "-1", "  " }) {
            assertEquals("UNAUTHORIZED", controller.login(new AuthController.LoginRequest(
                    null, null, null, null, bad), null).code());
        }
    }

    @Test
    @DisplayName("演示登录关闭时 userId 直传也不可用")
    void demo_login_disabled_returns_unauthorized() {
        AuthController controller = controller(new FakeAuth(), false);
        assertEquals("UNAUTHORIZED", controller.login(new AuthController.LoginRequest(
                null, null, null, null, "1001"), null).code());
    }

    @Test
    @DisplayName("用户名登录：验证码错误直接拒绝")
    void username_login_rejects_bad_captcha() {
        Fixture f = new Fixture();
        // 业务异常由 GlobalExceptionHandler 统一转成 Result，控制器本身不吞异常
        BizException e = assertThrows(BizException.class, () -> f.controller.login(
                new AuthController.LoginRequest("student", "abc12345", "cap-1", "WRONG", null), null));
        assertEquals(ErrorCode.CAPTCHA_INVALID, e.code());
    }

    @Test
    @DisplayName("用户名登录成功后返回数据库里的 user_id 与角色")
    void username_login_returns_db_identity() {
        Fixture f = new Fixture();
        f.captcha.answer = "AB12";
        var resp = f.controller.login(new AuthController.LoginRequest(
                "student", "abc12345", "cap-1", "ab12", null), null);
        assertEquals("OK", resp.code());
        assertEquals("uuid-001", resp.data().get("userId"));
        assertEquals("user", resp.data().get("role"));
        assertNotNull(resp.data().get("accessToken"));
    }

    @Test
    @DisplayName("密码错误返回 BAD_CREDENTIALS，且不签发 token")
    void username_login_rejects_wrong_password() {
        Fixture f = new Fixture();
        f.captcha.answer = "AB12";
        BizException e = assertThrows(BizException.class, () -> f.controller.login(
                new AuthController.LoginRequest("student", "wrong-pass", "cap-1", "AB12", null), null));
        assertEquals(ErrorCode.BAD_CREDENTIALS, e.code());
        assertEquals(1, f.users.loginFailures);
    }

    @Test
    @DisplayName("刷新后轮换，旧 refresh token 复用被拒绝")
    void refresh_rotates_and_rejects_replay() {
        FakeAuth auth = new FakeAuth();
        AuthController controller = controller(auth, true);
        var login = controller.login(new AuthController.LoginRequest(
                null, null, null, null, "1001"), null).data();
        String refreshToken = String.valueOf(login.get("refreshToken"));

        var refreshed = controller.refresh(new AuthController.RefreshRequest(refreshToken));
        assertEquals("OK", refreshed.code());
        String next = String.valueOf(refreshed.data().get("refreshToken"));
        assertNotEquals(refreshToken, next);

        assertEquals("UNAUTHORIZED", controller.refresh(
                new AuthController.RefreshRequest(refreshToken)).code());
    }

    @Test
    @DisplayName("登出撤销 refresh token，之后不能再用")
    void logout_revokes_refresh() {
        FakeAuth auth = new FakeAuth();
        AuthController controller = controller(auth, true);
        var data = controller.login(new AuthController.LoginRequest(
                null, null, null, null, "1001"), null).data();
        String refreshToken = String.valueOf(data.get("refreshToken"));

        assertEquals("OK", controller.logout("Bearer " + data.get("accessToken"),
                new AuthController.LogoutRequest(refreshToken)).code());
        assertEquals("UNAUTHORIZED", controller.refresh(
                new AuthController.RefreshRequest(refreshToken)).code());
    }

    private static final com.campusdash.domain.auth.ports.ForceLogoutPort NO_FORCE =
            new com.campusdash.domain.auth.ports.ForceLogoutPort() {
                @Override public void force(String userId) { }
                @Override public boolean isForced(String userId) { return false; }
                @Override public void clear(String userId) { }
            };

    private static AuthController controller(AuthPort auth, boolean demo) {
        return new AuthController(auth, new LoginUserUseCase(
                new EmptyUsers(), new FakeHasher(),
                new FakeCaptcha(), auth), new EmptyUsers(), NO_FORCE, demo);
    }

    /** 带一个学生账号的完整夹具 */
    private static final class Fixture {
        final FakeUsers users = new FakeUsers();
        final FakeCaptcha captcha = new FakeCaptcha();
        final FakeAuth auth = new FakeAuth();
        final AuthController controller;

        Fixture() {
            UserAccount student = new UserAccount("uuid-001", "student", "13812345678",
                    "hash:abc12345", Instant.now(), Instant.now(), Instant.now(),
                    UserAccount.ROLE_USER, "", 1L, 60, true, null, "", 0, "");
            users.byName.put("student", student);
            controller = new AuthController(auth,
                    new LoginUserUseCase(users, new FakeHasher(), captcha, auth), users, NO_FORCE, true);
        }
    }

    private static final class FakeAuth implements RefreshTokenPort {
        private final Map<String, String> refresh = new HashMap<>();
        private int seq;

        @Override public TokenPair loginWithRefresh(String userId) {
            return new TokenPair(access(userId), issueRefresh(userId));
        }
        @Override public Optional<TokenPair> refresh(String token) {
            String userId = refresh.remove(token);
            return userId == null ? Optional.empty()
                    : Optional.of(new TokenPair(access(userId), issueRefresh(userId)));
        }
        @Override public void revokeRefresh(String token) { refresh.remove(token); }
        @Override public String login(String userId) { return access(userId); }
        @Override public Optional<String> resolve(String token) { return Optional.of("1001"); }
        @Override public void logout(String token) { }

        private String access(String userId) { return "access-" + userId + "-" + (++seq); }
        private String issueRefresh(String userId) {
            String token = "refresh-" + userId + "-" + (++seq);
            refresh.put(token, userId);
            return token;
        }
    }

    private static final class FakeHasher implements PasswordHasher {
        @Override public String hash(String raw) { return "hash:" + raw; }
        @Override public boolean matches(String raw, String stored) { return ("hash:" + raw).equals(stored); }
    }

    private static final class FakeCaptcha implements CaptchaPort {
        String answer = "";
        @Override public Captcha create() { return new Captcha("cap-1", "", 300); }
        @Override public boolean verify(String id, String code) {
            return answer.equalsIgnoreCase(code == null ? "" : code.trim());
        }
    }

    private static final class FakeUsers implements UserRepository {
        final Map<String, UserAccount> byName = new HashMap<>();
        int loginFailures;

        @Override public Optional<UserAccount> findByUsername(String username) { return Optional.ofNullable(byName.get(username)); }
        @Override public Optional<UserAccount> findById(String userId) { return byName.values().stream().filter(u -> u.userId().equals(userId)).findFirst(); }
        @Override public Optional<UserAccount> findByPhone(String phone) { return Optional.empty(); }
        @Override public boolean existsByUsername(String username) { return byName.containsKey(username); }
        @Override public boolean existsByPhone(String phone) { return false; }
        @Override public void insert(UserAccount account) { byName.put(account.username(), account); }
        @Override public java.util.List<UserAccount> findAll() { return new java.util.ArrayList<>(byName.values()); }
        @Override public int countOnline() { return 0; }
        @Override public void markLoginSuccess(String userId, Instant at, String ip) { }
        @Override public void markOnline(String userId) { }
        @Override public void markOffline(String userId) { }
        @Override public int sweepOffline(Instant cutoff) { return 0; }
        @Override public void updateStatus(String userId, boolean enabled) { }
        @Override public void markLoginFailure(String userId) { loginFailures++; }
        @Override public void updatePassword(String userId, String hash, Instant at) { }
        @Override public boolean deleteById(String userId) { return byName.values().removeIf(u -> u.userId().equals(userId)); }
    }

    private static final class EmptyUsers implements UserRepository {
        @Override public Optional<UserAccount> findByUsername(String username) { return Optional.empty(); }
        @Override public Optional<UserAccount> findById(String userId) { return Optional.empty(); }
        @Override public Optional<UserAccount> findByPhone(String phone) { return Optional.empty(); }
        @Override public boolean existsByUsername(String username) { return false; }
        @Override public boolean existsByPhone(String phone) { return false; }
        @Override public void insert(UserAccount account) { }
        @Override public java.util.List<UserAccount> findAll() { return java.util.List.of(); }
        @Override public int countOnline() { return 0; }
        @Override public void markLoginSuccess(String userId, Instant at, String ip) { }
        @Override public void markOnline(String userId) { }
        @Override public void markOffline(String userId) { }
        @Override public int sweepOffline(Instant cutoff) { return 0; }
        @Override public void updateStatus(String userId, boolean enabled) { }
        @Override public void markLoginFailure(String userId) { }
        @Override public void updatePassword(String userId, String hash, Instant at) { }
        @Override public boolean deleteById(String userId) { return false; }
    }
}
