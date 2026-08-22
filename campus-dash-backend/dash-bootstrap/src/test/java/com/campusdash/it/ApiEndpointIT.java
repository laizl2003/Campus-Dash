package com.campusdash.it;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P4 API 层集成测试：走真实 HTTP，验证端点、鉴权、权限拒绝与 availableActions。
 *
 * 所有请求用 Bearer token（走正门），不用 X-User-Id 后门——
 * 后门留给压测，API 正确性必须用真实鉴权链路验证。
 * "后门关闭时 X-User-Id 无效"由 BackdoorDisabledIT 单独覆盖。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"dash.auth.allow-header-identity=true"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiEndpointIT {

    static final long PUB = 1001L;
    static final long RUNNER = 2001L;
    static final long ARBITRATOR = 9001L;

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    /**
     * P5 起必须注入：布隆过滤器只认"走过发布用例"的任务 id。
     * 本用例用 SQL 直接造数（为了快速铺满 7 个状态），绕过了 PublishErrandUseCase，
     * 所以要手工 registerExisting——否则详情接口被防穿透逻辑判为"不存在"。
     *
     * 这不是测试的特例，而是一个真实的运维约束：数据迁移、DBA 手工修数、
     * 压测批量造数等任何绕过应用写入 DB 的路径，都必须同步补登记布隆或触发重建。
     */
    @Autowired com.campusdash.domain.errand.ports.ErrandCachePort errandCache;

    private static String pubToken;
    private static String runnerToken;
    private static String arbitratorToken;
    private static long errandId;

    @BeforeAll
    static void requireMiddleware() {
        Assumptions.assumeTrue(MiddlewareAvailable.check(), "中间件未启动，跳过");
    }

    @SuppressWarnings("unchecked")
    private String login(TestRestTemplate rest, long userId) {
        var resp = rest.postForEntity("/api/auth/login",
                Map.of("userId", userId), Map.class);
        assertEquals(200, resp.getStatusCode().value());
        // 响应是 Result 包装：token 在 data 里，直接取顶层 body 是 null（实测踩过）
        return (String) ((Map<String, Object>) resp.getBody().get("data")).get("token");
    }

    private HttpEntity<Void> bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return new HttpEntity<>(h);
    }

    private HttpEntity<Map<String, Object>> bearerJson(String token, Map<String, Object> body) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.set("Content-Type", "application/json");
        return new HttpEntity<>(body, h);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(ResponseEntity<Map> resp) {
        assertEquals("OK", resp.getBody().get("code"), "响应码应为 OK，实际: " + resp.getBody());
        return (Map<String, Object>) resp.getBody().get("data");
    }

    private Object dataRaw(ResponseEntity<Map> resp) {
        assertEquals("OK", resp.getBody().get("code"), "响应码应为 OK，实际: " + resp.getBody());
        return resp.getBody().get("data");
    }

    @Test
    @Order(1)
    @DisplayName("未带 token 访问受保护端点应 401")
    void unauthorized_without_token() {
        var resp = rest.exchange("/api/errands", HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), Map.class);
        assertEquals(401, resp.getStatusCode().value());
    }

    @Test
    @Order(2)
    @DisplayName("登录后可走完整生命周期：发布→抢→确认→取货→送达→结算")
    void full_lifecycle_over_http() {
        pubToken = login(rest, PUB);
        runnerToken = login(rest, RUNNER);
        arbitratorToken = login(rest, ARBITRATOR);

        // 健康检查不需要登录
        var health = rest.getForEntity("/api/health", Map.class);
        assertEquals("OK", health.getBody().get("code"));

        // 发布（token 走正门）
        var pubResp = rest.exchange("/api/errands", HttpMethod.POST, bearerJson(pubToken,
                Map.of("type", "DELIVERY", "title", "api_it_" + UUID.randomUUID().toString().substring(0, 6),
                        "rewardCents", 2000, "slotTotal", 1)), Map.class);
        Map<String, Object> pubData = data(pubResp);
        // errandId 是字符串：雪花 ID 超过 JS 安全整数，Jackson 统一把 long 序列化为 string
        // （JacksonConfig），这里顺带证明了该行为
        errandId = Long.parseLong(String.valueOf(pubData.get("errandId")));

        // 钱包：发单人余额减少（托管转移走）
        var wallet = data(rest.exchange("/api/wallet", HttpMethod.GET, bearer(pubToken), Map.class));
        assertTrue(Long.parseLong(String.valueOf(wallet.get("availableCents"))) < 100000);

        // 抢单
        var grabResp = rest.exchange("/api/errands/" + errandId + "/grab", HttpMethod.POST,
                bearer(runnerToken), Map.class);
        assertEquals("OK", grabResp.getBody().get("code"), "抢单应成功: " + grabResp.getBody());

        // 确认接单
        var confirmResp = rest.exchange("/api/errands/" + errandId + "/confirm", HttpMethod.POST,
                bearer(runnerToken), Map.class);
        assertEquals("OK", confirmResp.getBody().get("code"), "确认应成功: " + confirmResp.getBody());

        // 取货
        var pickupResp = rest.exchange("/api/errands/" + errandId + "/pickup", HttpMethod.POST,
                bearer(runnerToken), Map.class);
        assertEquals("OK", pickupResp.getBody().get("code"), "取货应成功: " + pickupResp.getBody());

        // 送达
        var deliverResp = rest.exchange("/api/errands/" + errandId + "/deliver", HttpMethod.POST,
                bearer(runnerToken), Map.class);
        assertEquals("OK", deliverResp.getBody().get("code"), "送达应成功: " + deliverResp.getBody());

        // 发单人确认完成（结算）
        var settleResp = rest.exchange("/api/errands/" + errandId + "/settle", HttpMethod.POST,
                bearer(pubToken), Map.class);
        assertEquals("OK", settleResp.getBody().get("code"), "结算应成功: " + settleResp.getBody());
        assertEquals("SETTLED", data(settleResp).get("result"));

        // 时间线：应记录完整流转（DRAFT->PUBLISHED->LOCKED->ACCEPTED->PICKED_UP->DELIVERED->SETTLED）
        var timelineResp = rest.exchange("/api/errands/" + errandId + "/timeline",
                HttpMethod.GET, bearer(pubToken), Map.class);
        List<?> events = (List<?>) dataRaw(timelineResp);
        assertTrue(events.size() >= 6, "时间线应至少 6 步流转，实际 " + events.size());

        // 跑腿钱包到账
        var runnerWallet = data(rest.exchange("/api/wallet", HttpMethod.GET, bearer(runnerToken), Map.class));
        assertTrue(Long.parseLong(String.valueOf(runnerWallet.get("availableCents"))) > 5000,
                "跑腿余额应含结算所得");

        // 跑腿流水里能查到 SETTLE
        List<?> ledger = (List<?>) dataRaw(rest.exchange("/api/wallet/ledger",
                HttpMethod.GET, bearer(runnerToken), Map.class));
        assertTrue(ledger.stream().anyMatch(row -> "SETTLE".equals(((Map<?, ?>) row).get("refType"))),
                "跑腿流水应包含 SETTLE");
    }

    @Test
    @Order(3)
    @DisplayName("权限拒绝：非当事人操作返回对应业务错误码")
    void permission_denied() {
        // 发一个任务供操作
        var pubResp = rest.exchange("/api/errands", HttpMethod.POST, bearerJson(pubToken,
                Map.of("type", "BUY", "title", "perm_test", "rewardCents", 1000, "slotTotal", 1)), Map.class);
        long id = Long.parseLong(String.valueOf(data(pubResp).get("errandId")));

        // 跑腿试图取消（只有发单人能取消）
        var cancelResp = rest.exchange("/api/errands/" + id + "/cancel", HttpMethod.POST,
                bearer(runnerToken), Map.class);
        assertEquals("NOT_PUBLISHER", cancelResp.getBody().get("code"));

        // 路人试图抢发单人自己的任务？换个视角：发单人不能抢自己的任务，
        // 表现为 availableActions 不含 GRAB（见 Order(4)），这里验证非抢中者不能确认
        rest.exchange("/api/errands/" + id + "/grab", HttpMethod.POST, bearer(runnerToken), Map.class);
        var confirmByWrongRunner = rest.exchange("/api/errands/" + id + "/confirm", HttpMethod.POST,
                bearer(arbitratorToken), Map.class);
        assertEquals("NOT_CURRENT_GRABBER", confirmByWrongRunner.getBody().get("code"));
    }

    @Test
    @Order(4)
    @DisplayName("availableActions：状态 × 身份的动作集合与状态机一致")
    void available_actions_matrix() {
        // 用 SQL 直接铺 7 个状态的任务（最快），再验证各身份的 availableActions。
        // 这里直接造数而不是走 HTTP 流转：本用例测的是"动作集合的计算"，不是流转链路
        jdbc.update("DELETE FROM wallet_ledger WHERE ref_id BETWEEN 910000000000 AND 910000000006");
        jdbc.update("DELETE FROM escrow_order WHERE errand_id BETWEEN 910000000000 AND 910000000006");
        jdbc.update("DELETE FROM errand WHERE id BETWEEN 910000000000 AND 910000000006");

        String[] statuses = {"PUBLISHED", "LOCKED", "ACCEPTED", "PICKED_UP", "DELIVERED", "DISPUTED", "SETTLED"};
        long[] escrowIds = new long[7];
        for (int i = 0; i < 7; i++) {
            long id = 910_000_000_000L + i;
            jdbc.update("""
                    INSERT INTO errand (id, campus_id, publisher_id, grabber_id, type, title,
                                        reward_amount, slot_total, slot_taken, status, round, version)
                    VALUES (?, 1, ?, ?, 'DELIVERY', CONCAT('act_', ?), 1000, 1, ?, ?, 0, 3)
                    """, id, PUB, statuses[i].equals("PUBLISHED") ? null : RUNNER,
                    statuses[i],
                    statuses[i].equals("PUBLISHED") ? 0 : 1,
                    statuses[i]);
            // DELIVERED/SETTLED/DISPUTED 需要托管单，保证动作计算时资金状态合理
            escrowIds[i] = 910_100_000_000L + i;
            jdbc.update("INSERT INTO escrow_order (id, campus_id, errand_id, publisher_id, amount, status) "
                            + "VALUES (?, 1, ?, ?, 1000, 'HELD')", escrowIds[i], id, PUB);
            // 补登记布隆：SQL 造数绕过了发布用例
            errandCache.registerExisting(id);
        }

        // PUBLISHED：跑腿可抢（GRAB），发单人可取消（CANCEL），不能抢自己的
        assertActions(910_000_000_000L, RUNNER, List.of("GRAB"));
        assertActions(910_000_000_000L, PUB, List.of("CANCEL"));
        assertActions(910_000_000_000L, ARBITRATOR, List.of("GRAB"));

        // LOCKED：只有当前跑腿可确认。注意没有 DISPUTE——
        // 状态机 FROM_LOCKED 不含 DISPUTED：任务还没人开始执行，不存在争议标的，
        // 这条规则在 P1 定义状态机时就确立了，本用例最初写错期望值，恰好被测试纠正
        assertActions(910_000_000_001L, RUNNER, List.of("CONFIRM"));
        assertActions(910_000_000_001L, PUB, List.of());

        // ACCEPTED：跑腿可取货
        assertActions(910_000_000_002L, RUNNER, List.of("PICKUP", "DISPUTE"));
        assertActions(910_000_000_002L, PUB, List.of("DISPUTE"));

        // PICKED_UP：跑腿可送达
        assertActions(910_000_000_003L, RUNNER, List.of("DELIVER", "DISPUTE"));

        // DELIVERED：发单人可结算；跑腿可争议
        assertActions(910_000_000_004L, PUB, List.of("SETTLE", "DISPUTE"));
        assertActions(910_000_000_004L, RUNNER, List.of("DISPUTE"));

        // DISPUTED：只有仲裁员有 ARBITRATE
        assertActions(910_000_000_005L, ARBITRATOR, List.of("ARBITRATE"));
        assertActions(910_000_000_005L, PUB, List.of());
        assertActions(910_000_000_005L, RUNNER, List.of());

        // SETTLED 终态：所有人都没有动作
        assertActions(910_000_000_006L, PUB, List.of());
        assertActions(910_000_000_006L, RUNNER, List.of());
    }

    @SuppressWarnings("unchecked")
    private void assertActions(long id, long viewerTokenUser, List<String> expected) {
        String token = viewerTokenUser == PUB ? pubToken
                : viewerTokenUser == ARBITRATOR ? arbitratorToken : runnerToken;
        var resp = rest.exchange("/api/errands/" + id, HttpMethod.GET, bearer(token), Map.class);
        Map<String, Object> card = data(resp);
        List<String> actions = ((List<?>) card.get("availableActions")).stream()
                .map(String::valueOf).toList();
        assertEquals(expected.stream().sorted().toList(), actions.stream().sorted().toList(),
                "任务 " + id + " 对用户 " + viewerTokenUser + " 的动作集合");
    }

    @AfterEach
    void cleanupActionMatrix() {
        // 造数必须自己清理：availableActions 矩阵造的 HELD 托管单若残留，
        // 会让 SettleConcurrencyIT 的全局 L3 对账检出差异——测试之间不能互相污染（实测踩过）
        jdbc.update("DELETE FROM escrow_order WHERE errand_id BETWEEN 910000000000 AND 910000000006");
        jdbc.update("DELETE FROM errand WHERE id BETWEEN 910000000000 AND 910000000006");
    }

    @Test
    @Order(5)
    @DisplayName("列表 / 我的任务 / 站内消息 / 未读数端点可用")
    void read_endpoints() {
        var list = rest.exchange("/api/errands?campusId=1", HttpMethod.GET, bearer(runnerToken), Map.class);
        assertEquals("OK", list.getBody().get("code"));

        var mine = rest.exchange("/api/errands/mine?role=GRABBED_BY_ME", HttpMethod.GET, bearer(runnerToken), Map.class);
        assertEquals("OK", mine.getBody().get("code"));

        var notif = rest.exchange("/api/notifications", HttpMethod.GET, bearer(pubToken), Map.class);
        assertEquals("OK", notif.getBody().get("code"));

        var unread = rest.exchange("/api/notifications/unread", HttpMethod.GET, bearer(pubToken), Map.class);
        assertEquals("OK", unread.getBody().get("code"));
    }
}
