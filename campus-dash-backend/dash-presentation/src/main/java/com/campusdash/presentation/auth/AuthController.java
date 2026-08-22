package com.campusdash.presentation.auth;

import com.campusdash.domain.auth.ports.AuthPort;
import com.campusdash.domain.auth.ports.RefreshTokenPort;
import com.campusdash.shared.ErrorCode;
import com.campusdash.shared.Result;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 登录 / 刷新 / 登出。登录与刷新不走 AuthInterceptor（登录时还没有 token，刷新时 access token 可能已过期），
 * 由 WebConfig 的 excludePathPatterns 排除。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthPort authPort;

    public AuthController(AuthPort authPort) {
        this.authPort = authPort;
    }

    public record LoginRequest(Long userId) {}
    public record RefreshRequest(String refreshToken) {}

    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody LoginRequest req) {
        if (authPort instanceof RefreshTokenPort refreshTokenPort) {
            var pair = refreshTokenPort.loginWithRefresh(req.userId());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("token", pair.accessToken());       // 兼容旧前端
            data.put("accessToken", pair.accessToken());
            data.put("refreshToken", pair.refreshToken());
            data.put("userId", req.userId());
            return Result.ok(data);
        }
        String token = authPort.login(req.userId());
        return Result.ok(Map.of("token", token, "userId", req.userId()));
    }

    @PostMapping("/refresh")
    public Result<Map<String, Object>> refresh(@RequestBody RefreshRequest req) {
        if (!(authPort instanceof RefreshTokenPort refreshTokenPort)) {
            return Result.fail(ErrorCode.UNAUTHORIZED);
        }
        return refreshTokenPort.refresh(req.refreshToken())
                .map(pair -> {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("token", pair.accessToken());       // 兼容旧前端
                    data.put("accessToken", pair.accessToken());
                    data.put("refreshToken", pair.refreshToken());
                    return Result.ok(data);
                })
                .orElseGet(() -> Result.fail(ErrorCode.UNAUTHORIZED));
    }

    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader(value = "Authorization", required = false) String auth) {
        if (auth != null && auth.startsWith("Bearer ")) {
            authPort.logout(auth.substring(7).trim());
        }
        return Result.ok(null);
    }
}
