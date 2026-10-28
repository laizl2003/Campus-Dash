package com.campusdash.domain.auth.ports;

import java.util.Optional;

/**
 * 认证端口。P4 是有状态 Session（token 存 Redis），不是 JWT。
 *
 * 这是有意为之：先做最简单的形态，把"登录、token、拦截器"的骨架搭好；
 * JWT 与无状态鉴权的取舍（吊销难、续期、多端失效）留到 P5 做对比实现，
 * 有对照组才讲得清为什么选 JWT。
 */
public interface AuthPort {

    /** 登录：为 userId 签发 token。同一用户可持有多个 token（多端） */
    String login(String userId);

    /** token -> userId。不存在或已过期返回 empty */
    Optional<String> resolve(String token);

    /** 登出：只删除当前 token（当前浏览器） */
    void logout(String token);

    /** 有请求访问时续期当前 token（Session 模式）；默认无操作 */
    default void touch(String token) {
    }

    /** 当前登录有效的用户数（Redis ZSET 统计）；默认 0 */
    default int onlineCount() {
        return 0;
    }

    /** 指定用户是否还有有效登录会话 */
    default boolean isOnline(String userId) {
        return false;
    }

    /** 当前在线用户 id，按 auth:online 的 score 升序；默认空 */
    default java.util.List<String> onlineUserIds() {
        return java.util.List.of();
    }

    /** 强制下线：撤销该用户已签发的全部 access/refresh 凭证（默认无操作） */
    default void revokeAllSessions(String userId) {
    }
}
