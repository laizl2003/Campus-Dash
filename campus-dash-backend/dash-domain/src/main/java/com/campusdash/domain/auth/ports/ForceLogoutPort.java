package com.campusdash.domain.auth.ports;

/** 强制下线标记：Admin 踢人后，直到用户重新登录前拒绝其请求与新 WS 连接 */
public interface ForceLogoutPort {

    void force(String userId);

    boolean isForced(String userId);

    /** 用户重新登录成功后清除标记 */
    void clear(String userId);
}
