package com.campusdash.presentation.auth;

import com.campusdash.shared.BizException;
import com.campusdash.shared.ErrorCode;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * 当前请求者的用户 id。由 AuthInterceptor 解析 token 后放进 request attribute，
 * Controller 通过本类静态方法获取——比每个方法都传一遍参数干净。
 */
public final class CurrentUser {

    public static final String ATTR = "currentUserId";

    private CurrentUser() {
    }

    public static long get() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        Object v = attrs == null ? null : attrs.getAttribute(ATTR, RequestAttributes.SCOPE_REQUEST);
        if (v == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "未登录或会话已过期");
        }
        return (Long) v;
    }
}
