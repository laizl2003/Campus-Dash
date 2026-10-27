package com.campusdash.presentation.auth;

import com.campusdash.shared.BizException;
import com.campusdash.shared.ErrorCode;

/**
 * 当前请求者的用户 id，由 AuthInterceptor 解析 token 后写入 ThreadLocal。
 *
 * 为什么用 ThreadLocal 而不是 request attribute：
 *   Controller、用例、领域服务都能直接取到，不必层层传参；
 *   但必须在请求结束时 clear()，否则线程池复用会串号（拦截器 afterCompletion 负责）。
 *
 * 类型是 String：注册用户是 UUID，仲裁账号是 001，演示账号是 1001。
 */
public final class CurrentUser {

    /** 兼容旧代码里对 request attribute 的引用 */
    public static final String ATTR = "currentUserId";

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private CurrentUser() {
    }

    public static String get() {
        String userId = CURRENT.get();
        if (userId == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "未登录或会话已过期");
        }
        return userId;
    }

    public static void set(String userId) {
        CURRENT.set(userId);
    }

    public static void clear() {
        CURRENT.remove();
    }
}
