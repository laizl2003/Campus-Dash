package com.campusdash.infrastructure.grab;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SentinelGrabRateLimiterAdapterTest {

    @Test
    @DisplayName("Sentinel 热点参数限流按任务维度拒绝超阈值抢单")
    void sentinel_limiter_blocks_by_errand_hot_param() {
        SentinelGrabRateLimiterAdapter limiter = new SentinelGrabRateLimiterAdapter(
                true, 2, resourceName());

        assertTrue(limiter.tryPass(10001, "2001"));
        assertTrue(limiter.tryPass(10001, "2002"));
        assertFalse(limiter.tryPass(10001, "2003"));

        assertTrue(limiter.tryPass(10002, "2004"), "不同任务应按独立热点参数计数");
    }

    @Test
    @DisplayName("Sentinel 限流关闭时始终放行")
    void disabled_sentinel_limiter_always_passes() {
        SentinelGrabRateLimiterAdapter limiter = new SentinelGrabRateLimiterAdapter(
                false, 1, resourceName());

        assertTrue(limiter.tryPass(10001, "2001"));
        assertTrue(limiter.tryPass(10001, "2002"));
    }

    private static String resourceName() {
        return "campus_dash_grab_test_" + UUID.randomUUID();
    }
}
