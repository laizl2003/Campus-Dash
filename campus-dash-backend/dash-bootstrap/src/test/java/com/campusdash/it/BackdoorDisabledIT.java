package com.campusdash.it;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证后门开关关闭后，裸 X-User-Id 不再能冒充身份。
 *
 * 这个测试必须单独一个上下文（property 覆盖与 ApiEndpointIT 相反），
 * Spring 会缓存 context，property 不同就是不同 context。
 *
 * 这是安全边界测试：默认配置就是关闭后门，本用例证明默认配置下
 * "带 X-User-Id 但不带 token"的请求会被 401 拒绝，而不是被信任。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"dash.auth.allow-header-identity=false"})
class BackdoorDisabledIT {

    @Autowired TestRestTemplate rest;

    @BeforeAll
    static void requireMiddleware() {
        Assumptions.assumeTrue(MiddlewareAvailable.check(), "中间件未启动，跳过");
    }

    @Test
    @DisplayName("后门关闭：X-User-Id 直传被 401 拒绝，token 仍可用")
    void header_identity_rejected_when_backdoor_closed() {
        // 只带 X-User-Id，没有 token
        HttpHeaders h = new HttpHeaders();
        h.set("X-User-Id", "1001");
        var resp = rest.exchange("/api/errands", HttpMethod.GET, new HttpEntity<>(h), Map.class);
        assertEquals(401, resp.getStatusCode().value(), "后门关闭时 X-User-Id 不应被信任");

        // 登录后 token 依然有效（正门不受后门开关影响）
        var login = rest.postForEntity("/api/auth/login", Map.of("userId", 1001), Map.class);
        // token 在 Result.data 里，不在顶层（实测踩过）
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) login.getBody().get("data");
        String token = (String) data.get("token");
        HttpHeaders auth = new HttpHeaders();
        auth.setBearerAuth(token);
        var ok = rest.exchange("/api/errands", HttpMethod.GET, new HttpEntity<>(auth), Map.class);
        assertEquals(200, ok.getStatusCode().value());
        assertEquals("OK", ok.getBody().get("code"));
    }
}
