package com.campusdash.bench;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SpikeLoadClientTest {

    @Test
    @DisplayName("抢单压测客户端单独识别热点限流响应")
    void classifies_grab_rate_limited_response() {
        String body = "{\"code\":\"GRAB_RATE_LIMITED\",\"message\":\"当前任务过热，请稍后再试\"}";

        assertEquals(SpikeLoadClient.Outcome.RATE_LIMITED,
                SpikeLoadClient.classifyResponse(200, body));
    }

    @Test
    @DisplayName("抢单压测客户端兼容字符串和数字 errandId")
    void parses_string_and_numeric_errand_id() {
        assertEquals(216L, SpikeLoadClient.parseErrandId("{\"data\":{\"errandId\":\"216\"}}"));
        assertEquals(217L, SpikeLoadClient.parseErrandId("{\"data\":{\"errandId\":217}}"));
    }
}
