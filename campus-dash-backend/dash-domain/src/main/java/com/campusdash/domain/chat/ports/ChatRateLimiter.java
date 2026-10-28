package com.campusdash.domain.chat.ports;

/** 聊天发送限流：每人固定窗口内最多 N 条。 */
public interface ChatRateLimiter {
    boolean allow(String userId);
}
