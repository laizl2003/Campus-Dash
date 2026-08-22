package com.campusdash.presentation.auth;

import com.campusdash.domain.auth.ports.AuthPort;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Optional;

/**
 * 认证拦截器：解析 Bearer token 得到当前用户。
 *
 * 后门开关 dash.auth.allow-header-identity：
 *   - 默认 false：只认 token，X-User-Id 无效
 *   - test profile 与集成测试设为 true：保留 X-User-Id 直传，
 *     否则 P1 的 SpikeLoadClient 压测（2000 并发 × 每人先登录一次）会被登录流程拖垮。
 *
 * 这个开关必须只出现在非生产配置里——谁把它开进生产，
 * 等于任何人带个 Header 就能冒充任何人。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final AuthPort authPort;
    private final boolean allowHeaderIdentity;

    public AuthInterceptor(AuthPort authPort,
                           @Value("${dash.auth.allow-header-identity:false}") boolean allowHeaderIdentity) {
        this.authPort = authPort;
        this.allowHeaderIdentity = allowHeaderIdentity;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) {
        Long userId = null;

        // 后门：测试与压测直传 X-User-Id
        if (allowHeaderIdentity) {
            String header = req.getHeader("X-User-Id");
            if (header != null && !header.isBlank()) {
                try {
                    userId = Long.parseLong(header);
                } catch (NumberFormatException ignored) {
                    // 无效头忽略，继续走 token 路径
                }
            }
        }

        // 正门：Bearer token
        if (userId == null) {
            String auth = req.getHeader("Authorization");
            if (auth != null && auth.startsWith("Bearer ")) {
                Optional<Long> resolved = authPort.resolve(auth.substring(7).trim());
                userId = resolved.orElse(null);
            }
        }

        if (userId == null) {
            resp.setStatus(401);
            return false;
        }
        req.setAttribute(CurrentUser.ATTR, userId);
        return true;
    }
}
