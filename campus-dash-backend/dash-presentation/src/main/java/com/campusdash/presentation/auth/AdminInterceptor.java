package com.campusdash.presentation.auth;

import com.campusdash.domain.auth.model.UserAccount;
import com.campusdash.domain.auth.ports.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Optional;

/**
 * 管理端角色校验：只拦 /api/admin/**，要求当前账号 role = Admin。
 *
 * 它必须排在 AuthInterceptor 之后：前者负责"你是谁"（解析 token、写 ThreadLocal），
 * 后者负责"你能不能"。顺序错了会拿不到 userId。
 *
 * 401 vs 403：
 *   - 没有/无效 token → AuthInterceptor 返回 401（未认证）
 *   - 已认证但不是管理员 → 这里返回 403（已认证，无权限）
 * 前端据此区分"去登录"还是"回首页"。
 */
@Component
public class AdminInterceptor implements HandlerInterceptor {

    private final UserRepository users;

    public AdminInterceptor(UserRepository users) {
        this.users = users;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) {
        String userId;
        try {
            userId = CurrentUser.get();
        } catch (RuntimeException e) {
            resp.setStatus(401);
            return false;
        }
        Optional<UserAccount> account = users.findById(userId);
        if (account.isEmpty() || !account.get().isAdmin()) {
            // 不抛异常：拦截器层直接给 403，避免被全局异常处理器包装成 200 + code
            resp.setStatus(403);
            return false;
        }
        return true;
    }
}
