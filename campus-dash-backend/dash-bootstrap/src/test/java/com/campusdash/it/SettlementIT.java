package com.campusdash.it;

import com.campusdash.application.usecase.*;
import com.campusdash.domain.errand.model.ErrandType;
import com.campusdash.domain.wallet.model.AccountType;
import com.campusdash.domain.wallet.ports.WalletRepository;
import com.campusdash.domain.recon.ports.ReconRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;


/**
 * P3 结算端到端集成测试。
 *
 * 验证链路：发布→抢单→确认→取货→送达→结算→对账全绿。
 * 以及退款与幂等。
 */
@SpringBootTest(properties = {
        "dash.mq.enabled=false",
        "dash.settle.commission-rate=0.05",
        "dash.settle.auto-settle-seconds=2"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SettlementIT {

    @Autowired PublishErrandUseCase publishUseCase;
    @Autowired GrabErrandUseCase grabUseCase;
    @Autowired ConfirmErrandUseCase confirmUseCase;
    @Autowired PickUpErrandUseCase pickUpUseCase;
    @Autowired DeliverErrandUseCase deliverUseCase;
    @Autowired SettleErrandUseCase settleUseCase;
    @Autowired RefundErrandUseCase refundUseCase;
    @Autowired ReconRepository reconRepository;
    @Autowired WalletRepository walletRepository;
    @Autowired JdbcTemplate jdbc;

    @BeforeAll
    static void requireMiddleware() {
        Assumptions.assumeTrue(MiddlewareAvailable.check(), "中间件未启动，跳过");
    }

    @BeforeEach
    void resetAccounts() {
        // 完全重置资金状态（包括流水与托管单），让本测试可独立验证 L2/L3
        jdbc.update("DELETE FROM wallet_ledger");
        jdbc.update("DELETE FROM escrow_order");
        jdbc.update("UPDATE wallet_account SET available = 100000, frozen = 0 WHERE owner_id = 1001 AND owner_type = 'USER'");
        jdbc.update("UPDATE wallet_account SET available = 5000, frozen = 0 WHERE owner_id = 2001 AND owner_type = 'USER'");
        jdbc.update("UPDATE wallet_account SET available = 0, frozen = 0 WHERE owner_type = 'ESCROW'");
        jdbc.update("UPDATE wallet_account SET available = 0, frozen = 0 WHERE owner_type = 'COMMISSION'");
        jdbc.update("DELETE FROM recon_diff");
    }

    @Test
    @Order(1)
    @DisplayName("端到端结算：跑腿+佣金==托管额，对账零差异")
    void full_lifecycle_settle() {
        // 发布（托管 2000 分）
        var pub = publishUseCase.publish(new PublishErrandUseCase.Command(
                1L, 1001L, ErrandType.DELIVERY, "settle_test", 2000L, 1));
        long errandId = pub.errandId();

        // 抢单+确认
        grabUseCase.grab(new GrabErrandUseCase.Command(errandId, 2001L, "req-settle-1", 60));
        confirmUseCase.confirm(new ConfirmErrandUseCase.Command(errandId, 2001L));

        // 取货：P4 补上用例后走真实链路，不再用 SQL 绕过
        pickUpUseCase.pickUp(errandId, 2001L);
        // 送达
        deliverUseCase.deliver(errandId, 2001L);

        // 结算
        var result = settleUseCase.settle(errandId, 1001L);
        assertEquals(SettleErrandUseCase.Result.SETTLED, result);

        // 验证余额
        long runnerAvailable = walletRepository.findByOwner(2001L, AccountType.USER).orElseThrow().available().cents();
        long commissionAvailable = walletRepository.findByOwner(-2L, AccountType.COMMISSION).orElseThrow().available().cents();
        long escrowAvailable = walletRepository.findByOwner(-1L, AccountType.ESCROW).orElseThrow().available().cents();

        // 佣金 = 2000 * 0.05 = 100，跑腿 = 2000 - 100 = 1900
        assertEquals(5000 + 1900, runnerAvailable, "跑腿余额");
        assertEquals(100, commissionAvailable, "佣金余额");
        assertEquals(0, escrowAvailable, "托管账户应清零");

        // 跑腿所得 + 佣金 == 原托管金额（不变式）
        assertEquals(2000, 1900 + 100);

        // 对账零差异：L1 借贷平衡
        assertEquals(0, reconRepository.debitMinusCredit(), "L1 借贷不平");
        assertTrue(reconRepository.findSnapshotDiffs().isEmpty(), "L2 快照不一致");
        assertTrue(reconRepository.findEscrowClosureDiffs().isEmpty(), "L3 托管不闭环");
    }

    @Test
    @Order(2)
    @DisplayName("重复结算幂等：不会重复打钱")
    void settle_idempotent() {
        var pub = publishUseCase.publish(new PublishErrandUseCase.Command(
                1L, 1001L, ErrandType.DELIVERY, "settle_idem", 1000L, 1));
        long errandId = pub.errandId();
        grabUseCase.grab(new GrabErrandUseCase.Command(errandId, 2001L, "req-idem-1", 60));
        confirmUseCase.confirm(new ConfirmErrandUseCase.Command(errandId, 2001L));
        pickUpUseCase.pickUp(errandId, 2001L);
        deliverUseCase.deliver(errandId, 2001L);

        assertEquals(SettleErrandUseCase.Result.SETTLED, settleUseCase.settle(errandId, 1001L));
        // 第二次
        assertEquals(SettleErrandUseCase.Result.ALREADY_SETTLED, settleUseCase.settle(errandId, 1001L));

        // 跑腿只加了一次
        long runnerAvailable = walletRepository.findByOwner(2001L, AccountType.USER).orElseThrow().available().cents();
        assertEquals(5000 + 950, runnerAvailable); // 1000 * 0.95 = 950
    }

    @Test
    @Order(3)
    @DisplayName("退款闭环：发布后取消，余额回到初始值")
    void cancel_and_refund() {
        var pub = publishUseCase.publish(new PublishErrandUseCase.Command(
                1L, 1001L, ErrandType.BUY, "refund_test", 3000L, 1));
        long errandId = pub.errandId();

        var result = refundUseCase.cancelAndRefund(errandId, 1001L);
        assertEquals(RefundErrandUseCase.Result.REFUNDED, result);

        // 发单人余额恢复
        long available = walletRepository.findByOwner(1001L, AccountType.USER).orElseThrow().available().cents();
        assertEquals(100000L, available, "退款后余额应恢复到初始值");

        // 托管账户清零
        long escrow = walletRepository.findByOwner(-1L, AccountType.ESCROW).orElseThrow().available().cents();
        assertEquals(0L, escrow);

        // 对账零差异
        assertEquals(0, reconRepository.debitMinusCredit(), "L1");
        assertTrue(reconRepository.findSnapshotDiffs().isEmpty(), "L2");
    }
}
