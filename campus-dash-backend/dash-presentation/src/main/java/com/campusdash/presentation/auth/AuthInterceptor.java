package com.campusdash.presentation.auth;

import com.campusdash.domain.auth.ports.AuthPort;
import com.campusdash.domain.auth.ports.ForceLogoutPort;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Optional;

/**
 * 认证拦截器：把 token 换成 userId，写进 ThreadLocal。
 *
 * 正门只认 Bearer token；X-User-Id 后门默认关闭，仅压测/本地调试可开。
 * 请求结束必须 clear()，否则 Tomcat 复用线程时下一个请求会拿到上一个用户的身份。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final AuthPort authPort;
    private final ForceLogoutPort forceLogoutPort;
    private final boolean allowHeaderIdentity;

    public AuthInterceptor(AuthPort authPort, ForceLogoutPort forceLogoutPort,
                           @Value("${dash.auth.allow-header-identity:false}") boolean allowHeaderIdentity) {
        this.authPort = authPort;
        this.forceLogoutPort = forceLogoutPort;
        this.allowHeaderIdentity = allowHeaderIdentity;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) {
        String userId = null;

        // 后门：测试与压测直传 X-User-Id（生产必须关闭）
        if (allowHeaderIdentity) {
            String header = req.getHeader("X-User-Id");
            if (header != null && !header.isBlank()) {
                userId = header.trim();
            }
        }

        // 正门：Bearer token。token 的 sub 就是登录时查库得到的 user_id
        if (userId == null) {
            String auth = req.getHeader("Authorization");
            if (auth != null && auth.startsWith("Bearer ")) {
                Optional<String> resolved = authPort.resolve(auth.substring(7).trim());
                userId = resolved.orElse(null);
            }
        }

        if (userId == null) {
            resp.setStatus(401);
            return false;
        }
        // 被 Admin 强制下线的用户：重新登录前一律 401
        if (forceLogoutPort.isForced(userId)) {
            resp.setStatus(401);
            return false;
        }
        CurrentUser.set(userId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse resp, Object handler, Exception ex) {
        CurrentUser.clear();
    }
}
