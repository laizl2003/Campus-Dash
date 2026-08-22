package com.campusdash.it;

import com.campusdash.application.usecase.PublishErrandUseCase;
import com.campusdash.domain.errand.model.ErrandType;
import com.campusdash.domain.wallet.model.AccountType;
import com.campusdash.domain.wallet.ports.WalletRepository;
import com.campusdash.shared.BizException;
import com.campusdash.shared.ErrorCode;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 发布任务的资金托管集成测试：验证一个本地事务内的托管闭环。
 */
@SpringBootTest
class PublishEscrowIT {

    @Autowired PublishErrandUseCase publishUseCase;
    @Autowired WalletRepository walletRepository;
    @Autowired JdbcTemplate jdbc;

    private static BenchRun run;

    @BeforeAll
    static void requireMiddleware() {
        Assumptions.assumeTrue(MiddlewareAvailable.check(), "中间件未启动，跳过");
        run = BenchRun.start("IT-ESCROW");
    }

    @AfterAll
    static void finishRun() {
        if (run != null) {
            run.finish("PASS", "{\"scenario\":\"IT-ESCROW\",\"ledgerBalanced\":true}");
            run.close();
        }
    }

    @BeforeEach
    void reset() {
        jdbc.update("UPDATE wallet_account SET available = 100000, frozen = 0 WHERE owner_id = 1001 AND owner_type = 'USER'");
        jdbc.update("UPDATE wallet_account SET available = 0, frozen = 0 WHERE owner_type = 'ESCROW'");
    }

    @Test
    @DisplayName("发布任务时冻结悬赏金，借贷双写且金额相等")
    void publish_freezes_reward_with_balanced_ledger() {
        long before = walletRepository.findByOwner(1001L, AccountType.USER).orElseThrow().available().cents();

        var result = publishUseCase.publish(new PublishErrandUseCase.Command(
                1L, 1001L, ErrandType.DELIVERY, "bench_托管测试", 1500L, 1));
        run.track(result.errandId());

        // P3 修正：托管是资金转移（不是冻结），钱真的离开发单人进入托管账户。
        // 所以发单人 available 减少、frozen 仍为 0；托管账户 available 增加。
        var account = walletRepository.findByOwner(1001L, AccountType.USER).orElseThrow();
        assertEquals(before - 1500L, account.available().cents());
        assertEquals(0L, account.frozen().cents(), "转移模型不写 frozen");

        // 托管账户余额增加
        var escrowAccount = walletRepository.findByOwner(-1L, AccountType.ESCROW).orElseThrow();
        assertEquals(1500L, escrowAccount.available().cents());

        // 托管单已 HELD
        String escrowStatus = jdbc.queryForObject(
                "SELECT status FROM escrow_order WHERE errand_id = ?", String.class, result.errandId());
        assertEquals("HELD", escrowStatus);

        // 复式记账：这笔业务的借贷金额必须相等
        Long debit = jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount),0) FROM wallet_ledger
                 WHERE biz_no = ? AND direction = 'DEBIT'
                """, Long.class, "escrow:" + result.errandId());
        Long credit = jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount),0) FROM wallet_ledger
                 WHERE biz_no = ? AND direction = 'CREDIT'
                """, Long.class, "escrow:" + result.errandId());
        assertEquals(1500L, debit);
        assertEquals(debit, credit, "借贷必须平衡");
    }

    @Test
    @DisplayName("余额不足时整个事务回滚：任务不会进入 PUBLISHED")
    void insufficient_balance_rolls_back_everything() {
        Integer errandsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM errand", Integer.class);

        BizException ex = assertThrows(BizException.class, () ->
                publishUseCase.publish(new PublishErrandUseCase.Command(
                        1L, 1001L, ErrandType.BUY, "bench_超额悬赏", 999999999L, 1)));
        assertEquals(ErrorCode.INSUFFICIENT_BALANCE, ex.code());

        // 事务回滚：任务没有落库，余额没有变化
        Integer errandsAfter = jdbc.queryForObject("SELECT COUNT(*) FROM errand", Integer.class);
        assertEquals(errandsBefore, errandsAfter, "余额不足时不应留下任务记录");

        var account = walletRepository.findByOwner(1001L, AccountType.USER).orElseThrow();
        assertEquals(100000L, account.available().cents());
        assertEquals(0L, account.frozen().cents());
    }
}
