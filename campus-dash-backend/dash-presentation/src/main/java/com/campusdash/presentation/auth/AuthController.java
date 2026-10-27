package com.campusdash.presentation.auth;

import com.campusdash.application.usecase.LoginUserUseCase;
import com.campusdash.domain.auth.model.UserAccount;
import com.campusdash.domain.auth.ports.AuthPort;
import com.campusdash.domain.auth.ports.ForceLogoutPort;
import com.campusdash.domain.auth.ports.UserRepository;
import com.campusdash.domain.auth.ports.RefreshTokenPort;
import com.campusdash.shared.BizException;
import com.campusdash.shared.ErrorCode;
import com.campusdash.shared.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 登录 / 刷新 / 登出。
 *
 * 登录有两条路径：
 *   1. 正门：username + password + captcha → 查库拿 user_id → 签发 token（token 的 sub 就是 user_id）
 *   2. 后门：{ "userId": "1001" } 演示登录，只给测试/压测用，
 *      由 dash.auth.demo-login 控制（默认 true，生产必须 false）
 *
 * 登录与刷新不走 AuthInterceptor（登录时还没有 token，刷新时 access 可能已过期），
 * 由 WebConfig 的 excludePathPatterns 排除。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthPort authPort;
    private final LoginUserUseCase loginUseCase;
    private final UserRepository users;
    private final ForceLogoutPort forceLogoutPort;
    private final boolean demoLoginEnabled;

    public AuthController(AuthPort authPort,
                          LoginUserUseCase loginUseCase,
                          UserRepository users,
                          ForceLogoutPort forceLogoutPort,
                          @Value("${dash.auth.demo-login:true}") boolean demoLoginEnabled) {
        this.authPort = authPort;
        this.loginUseCase = loginUseCase;
        this.users = users;
        this.forceLogoutPort = forceLogoutPort;
        this.demoLoginEnabled = demoLoginEnabled;
    }

    /** 新字段与演示字段放在同一个 record 里：有 username 走正门，没有才看 userId */
    public record LoginRequest(String username, String password, String captchaId,
                               String captchaCode, String userId) {
    }

    public record RefreshRequest(String refreshToken) {}
    public record LogoutRequest(String refreshToken) {}

    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody LoginRequest req, HttpServletRequest http) {
        if (req == null) {
            return Result.fail(ErrorCode.UNAUTHORIZED);
        }

        if (req.username() != null && !req.username().isBlank()) {
            LoginUserUseCase.LoggedIn logged = loginUseCase.login(new LoginUserUseCase.Command(
                    req.username(), req.password(), req.captchaId(), req.captchaCode(), clientIp(http)));
            forceLogoutPort.clear(logged.userId());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("token", logged.accessToken());          // 兼容旧前端
            data.put("accessToken", logged.accessToken());
            data.put("refreshToken", logged.refreshToken());
            data.put("userId", logged.userId());
            data.put("username", logged.username());
            data.put("role", logged.role());
            return Result.ok(data);
        }

        // 演示登录：只发 token，不查账号表
        if (!demoLoginEnabled) {
            return Result.fail(ErrorCode.UNAUTHORIZED);
        }
        String userId = req.userId();
        if (userId == null || userId.isBlank() || userId.startsWith("-")) {
            return Result.fail(ErrorCode.UNAUTHORIZED);
        }
        userId = userId.trim();
        users.findById(userId).ifPresent(user -> {
            if (!user.enabled()) {
                throw new BizException(ErrorCode.ACCOUNT_DISABLED);
            }
        });
        forceLogoutPort.clear(userId);
        users.markOnline(userId);
        if (authPort instanceof RefreshTokenPort refreshTokenPort) {
            var pair = refreshTokenPort.loginWithRefresh(userId);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("token", pair.accessToken());
            data.put("accessToken", pair.accessToken());
            data.put("refreshToken", pair.refreshToken());
            data.put("userId", userId);
            return Result.ok(data);
        }
        String token = authPort.login(userId);
        return Result.ok(Map.of("token", token, "userId", userId));
    }

    /**
     * 当前登录身份：刷新页面后用它在服务端重新确认 role，
     * 避免"本地没有 dash_role / 角色被改过"时进错界面。
     * 走 AuthInterceptor（不在 exclude 列表里），所以 userId 一定是校验过的。
     */
    @GetMapping("/me")
    public Result<Map<String, Object>> me() {
        String userId = CurrentUser.get();
        // 演示身份（1001/2001/...）不在 user 表里：按普通用户返回，
        // 不能用 401 —— 那会让前端的 401 刷新逻辑把演示会话整个登出。
        UserAccount account = users.findById(userId).orElse(null);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", userId);
        data.put("username", account == null ? userId : account.username());
        data.put("role", account == null ? "user" : account.role());
        return Result.ok(data);
    }

    @PostMapping("/refresh")
    public Result<Map<String, Object>> refresh(@RequestBody RefreshRequest req) {
        if (!(authPort instanceof RefreshTokenPort refreshTokenPort)) {
            return Result.fail(ErrorCode.UNAUTHORIZED);
        }
        return refreshTokenPort.refresh(req.refreshToken())
                .map(pair -> {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("token", pair.accessToken());
                    data.put("accessToken", pair.accessToken());
                    data.put("refreshToken", pair.refreshToken());
                    return Result.ok(data);
                })
                .orElseGet(() -> Result.fail(ErrorCode.UNAUTHORIZED));
    }

    @PostMapping("/heartbeat")
    public Result<Void> heartbeat() {
        users.markOnline(CurrentUser.get());
        return Result.ok(null);
    }

    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader(value = "Authorization", required = false) String auth,
                               @RequestBody(required = false) LogoutRequest body) {
        if (auth != null && auth.startsWith("Bearer ")) {
            String access = auth.substring(7).trim();
            authPort.resolve(access).ifPresent(users::markOffline);
            authPort.logout(access);
        }
        // access token 进黑名单只挡住剩余有效期；refresh 若留在 Redis，登出后仍能换新 access
        if (authPort instanceof RefreshTokenPort refreshTokenPort
                && body != null && body.refreshToken() != null) {
            refreshTokenPort.revokeRefresh(body.refreshToken().trim());
        }
        return Result.ok(null);
    }

    /** nginx / 网关后会带 X-Forwarded-For，取第一个地址 */
    private static String clientIp(HttpServletRequest request) {
        if (request == null) {
            return "";
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr() == null ? "" : request.getRemoteAddr();
    }
}
