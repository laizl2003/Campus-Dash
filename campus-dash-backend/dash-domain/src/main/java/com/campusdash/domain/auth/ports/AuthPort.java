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
    String login(long userId);

    /** token -> userId。不存在或已过期返回 empty */
    Optional<Long> resolve(String token);

    /** 登出：删除 token */
    void logout(String token);
}
