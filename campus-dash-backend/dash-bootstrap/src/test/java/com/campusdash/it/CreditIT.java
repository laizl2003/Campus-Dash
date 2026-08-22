package com.campusdash.it;

import com.campusdash.application.usecase.*;
import com.campusdash.domain.credit.ports.CreditRepository;
import com.campusdash.domain.errand.model.ErrandType;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 信用分专项验证：
 *   1. 结算成功 → 跑腿 +2，且与资金同事务（无"钱到账分没变"的中间态）
 *   2. 重复结算不重复计分（biz_no 幂等）
 *   3. 信用分低于门槛 → 抢单被拦（资格后端化，不再信客户端传参）
 */
@SpringBootTest(properties = {"dash.mq.enabled=false", "dash.credit.min-score=40"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CreditIT {

    static final long PUB = 1001L;
    static final long RUNNER = 2001L;

    @Autowired PublishErrandUseCase publishUseCase;
    @Autowired GrabErrandUseCase grabUseCase;
    @Autowired ConfirmErrandUseCase confirmUseCase;
    @Autowired PickUpErrandUseCase pickUpUseCase;
    @Autowired DeliverErrandUseCase deliverUseCase;
    @Autowired SettleErrandUseCase settleUseCase;
    @Autowired CreditRepository creditRepository;
    @Autowired JdbcTemplate jdbc;

    @BeforeAll
    static void requireMiddleware() {
        Assumptions.assumeTrue(MiddlewareAvailable.check(), "中间件未启动，跳过");
    }

    @BeforeEach
    void reset() {
        jdbc.update("UPDATE wallet_account SET available = 100000, frozen = 0 WHERE owner_id = 1001 AND owner_type = 'USER'");
        jdbc.update("UPDATE wallet_account SET available = 5000, frozen = 0 WHERE owner_id = 2001 AND owner_type = 'USER'");
        jdbc.update("DELETE FROM credit_event");
        jdbc.update("DELETE FROM credit_score");
    }

    @AfterEach
    void cleanup() {
        // low_credit 用例会把 2001 的分数打成 30，必须清掉——
        // 测试库共享，残留的低分会让后续所有测试类的抢单被 L2 拦截（实测踩过）
        jdbc.update("DELETE FROM credit_event");
        jdbc.update("DELETE FROM credit_score");
    }

    private long publishAndDeliver(String tag) {
        var pub = publishUseCase.publish(new PublishErrandUseCase.Command(
                1L, PUB, ErrandType.DELIVERY, "credit_" + tag + "_" + UUID.randomUUID().toString().substring(0, 6), 1000L, 1));
        long id = pub.errandId();
        grabUseCase.grab(new GrabErrandUseCase.Command(id, RUNNER, UUID.randomUUID().toString()));
        confirmUseCase.confirm(new ConfirmErrandUseCase.Command(id, RUNNER));
        pickUpUseCase.pickUp(id, RUNNER);
        deliverUseCase.deliver(id, RUNNER);
        return id;
    }

    @Test
    @Order(1)
    @DisplayName("结算成功 → 跑腿信用 +2，与资金同事务无中间态")
    void settle_increases_credit() {
        long id = publishAndDeliver("结算加分");
        int before = creditRepository.scoreOf(RUNNER);
        assertEquals(60, before, "初始分应为 60");

        var r = settleUseCase.settle(id, PUB);
        assertEquals(SettleErrandUseCase.Result.SETTLED, r);

        // 结算返回即分数已变：同事务提交，不存在"钱到账分没变"的窗口
        assertEquals(62, creditRepository.scoreOf(RUNNER), "结算后应 +2");
        assertEquals(1, creditRepository.recentEvents(RUNNER, 30, 10).size(), "恰好一条信用事件");
    }

    @Test
    @Order(2)
    @DisplayName("重复结算不重复计分（biz_no 幂等）")
    void settle_idempotent_credit() {
        long id = publishAndDeliver("幂等");
        settleUseCase.settle(id, PUB);
        settleUseCase.settle(id, PUB); // 第二次：ALREADY_SETTLED

        assertEquals(62, creditRepository.scoreOf(RUNNER), "重复结算不应重复计分");
        assertEquals(1, creditRepository.recentEvents(RUNNER, 30, 10).size());
    }

    @Test
    @Order(3)
    @DisplayName("信用分低于门槛 → 抢单被拦（资格后端化）")
    void low_credit_blocks_grab() {
        // 把跑腿分数打到门槛以下
        jdbc.update("INSERT INTO credit_score (user_id, score, version) VALUES (?, 30, 1)", RUNNER);

        var pub = publishUseCase.publish(new PublishErrandUseCase.Command(
                1L, PUB, ErrandType.DELIVERY,
                "credit_拦截_" + UUID.randomUUID().toString().substring(0, 6), 1000L, 1));

        // 客户端就算传高分（旧接口兼容参数）也没用——后端读自己的 credit_score
        var r = grabUseCase.grab(new GrabErrandUseCase.Command(pub.errandId(), RUNNER,
                UUID.randomUUID().toString(), 100));
        assertEquals(com.campusdash.shared.ErrorCode.CREDIT_TOO_LOW, r.code(),
                "低信用应被拦截，且客户端传的 100 分不生效");
    }
}
