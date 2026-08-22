package com.campusdash.infrastructure.grab;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LocalGrabRateLimiterAdapterTest {

    @Test
    @DisplayName("同一任务每秒超过阈值后被限流")
    void same_errand_is_limited_by_per_second_threshold() {
        LocalGrabRateLimiterAdapter limiter = new LocalGrabRateLimiterAdapter(true, 2);

        assertTrue(limiter.tryPass(10001L, 2001L));
        assertTrue(limiter.tryPass(10001L, 2002L));
        assertFalse(limiter.tryPass(10001L, 2003L));

        assertTrue(limiter.tryPass(10002L, 2004L), "不同任务应使用独立热点计数");
    }

    @Test
    @DisplayName("限流关闭时始终放行")
    void disabled_limiter_always_passes() {
        LocalGrabRateLimiterAdapter limiter = new LocalGrabRateLimiterAdapter(false, 1);

        assertTrue(limiter.tryPass(10001L, 2001L));
        assertTrue(limiter.tryPass(10001L, 2002L));
    }
}
