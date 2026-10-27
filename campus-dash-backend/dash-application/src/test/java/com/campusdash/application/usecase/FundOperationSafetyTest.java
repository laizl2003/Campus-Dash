package com.campusdash.application.usecase;

import com.campusdash.domain.credit.ports.CreditRankingPort;
import com.campusdash.domain.credit.ports.CreditRepository;
import com.campusdash.domain.errand.model.Errand;
import com.campusdash.domain.errand.model.ErrandStatus;
import com.campusdash.domain.errand.model.ErrandType;
import com.campusdash.domain.errand.ports.ErrandCachePort;
import com.campusdash.domain.errand.ports.ErrandRepository;
import com.campusdash.domain.auth.ports.UserRepository;
import com.campusdash.domain.notify.ports.RealtimeNotifier;
import com.campusdash.domain.config.ports.PlatformConfigPort;
import com.campusdash.domain.voucher.ports.VoucherRepository;
import com.campusdash.domain.wallet.model.*;
import com.campusdash.domain.wallet.ports.FundAuditPort;
import com.campusdash.domain.wallet.ports.FundEventPort;
import com.campusdash.domain.wallet.ports.WalletRepository;
import com.campusdash.shared.BizException;
import com.campusdash.shared.ErrorCode;
import com.campusdash.shared.Money;
import com.campusdash.shared.SnowflakeIdGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

/** 通过真实 Spring 事务边界验证资金操作的授权、阶段校验和失败回滚。 */
class FundOperationSafetyTest {
    private static final long ID = 10001L;
    private static final String PUBLISHER = "1001";
    private static final String RUNNER = "2001";
    private static final String ARBITRATOR = "9007";

    @Test
    void settlement_rejects_other_users_even_after_completion() {
        for (ErrandStatus status : List.of(ErrandStatus.DELIVERED, ErrandStatus.SETTLED)) {
            Fixture f = new Fixture(status);
            assertCode(ErrorCode.NOT_PUBLISHER, () -> f.settle().settle(ID, RUNNER));
            assertEquals(0, f.walletReads);
            f.assertUntouched();
        }
    }

    @Test
    void settlement_rejects_every_stage_before_delivery_and_disputes() {
        for (ErrandStatus status : ErrandStatus.values()) {
            if (status == ErrandStatus.DELIVERED || status == ErrandStatus.SETTLED) continue;
            Fixture f = new Fixture(status);
            assertCode(ErrorCode.ILLEGAL_STATE_TRANSITION, () -> f.settle().settle(ID, PUBLISHER));
            assertEquals(0, f.walletReads, status.name());
            f.assertUntouched();
        }
    }

    @Test
    void settlement_accepts_publisher_and_internal_worker_and_balances_ledger() {
        for (String actor : List.of(PUBLISHER, Errand.SYSTEM_OPERATOR)) {
            Fixture f = new Fixture(ErrandStatus.DELIVERED);
            assertEquals(SettleErrandUseCase.Result.SETTLED, f.settle().settle(ID, actor));
            assertEquals(1, f.tx.commits);
            assertEquals(0, f.tx.rollbacks);
            assertEquals(List.of("casEscrowStatus", "casSettle", "status:DELIVERED:SETTLED",
                    "debit:1:2000", "credit:2001:1900", "credit:2:100",
                    "ledger:DEBIT:2000", "ledger:CREDIT:1900", "ledger:CREDIT:100", "creditEvent"),
                    f.tx.committed);
        }
    }

    @Test
    void authorized_settlement_retries_are_idempotent() {
        Fixture f = new Fixture(ErrandStatus.SETTLED);
        assertEquals(SettleErrandUseCase.Result.ALREADY_SETTLED, f.settle().settle(ID, PUBLISHER));
        assertEquals(SettleErrandUseCase.Result.ALREADY_SETTLED, f.settle().settle(ID, Errand.SYSTEM_OPERATOR));
        assertEquals(0, f.walletReads);
        f.assertUntouched();
    }

    @Test
    void settlement_rolls_back_every_failed_cas_including_partial_account_updates() {
        for (String failed : List.of("casEscrowStatus", "casSettle", "debit:1:2000",
                "credit:2001:1900", "credit:2:100")) {
            Fixture f = new Fixture(ErrandStatus.DELIVERED);
            f.failAt = failed;
            assertEquals(SettleErrandUseCase.Result.CONFLICT, f.settle().settle(ID, PUBLISHER), failed);
            f.assertRolledBack();
        }
    }

    @Test
    void arbitration_checks_configured_identity_before_reading_or_idempotent_return() {
        for (ErrandStatus status : List.of(ErrandStatus.DISPUTED, ErrandStatus.SETTLED, ErrandStatus.REFUNDED)) {
            for (var favor : ArbitrateErrandUseCase.Favor.values()) {
                Fixture f = new Fixture(status);
                assertCode(ErrorCode.UNAUTHORIZED, () -> f.arbitrate().arbitrate(ID, favor, "9001"));
                assertEquals(0, f.errandReads);
                f.assertUntouched();
            }
        }
    }

    @Test
    void configured_arbitrator_can_retry_terminal_tasks_without_repaying() {
        for (ErrandStatus status : List.of(ErrandStatus.SETTLED, ErrandStatus.REFUNDED)) {
            Fixture f = new Fixture(status);
            assertEquals(ArbitrateErrandUseCase.Result.ALREADY_DONE,
                    f.arbitrate().arbitrate(ID, ArbitrateErrandUseCase.Favor.RUNNER, ARBITRATOR));
            f.assertUntouched();
        }
    }

    @Test
    void arbitration_to_runner_commits_through_the_transactional_step_proxy() {
        Fixture f = new Fixture(ErrandStatus.DISPUTED);
        assertEquals(ArbitrateErrandUseCase.Result.SETTLED_TO_RUNNER,
                f.arbitrate().arbitrate(ID, ArbitrateErrandUseCase.Favor.RUNNER, ARBITRATOR));
        assertEquals(1, f.tx.commits);
        assertEquals(0, f.tx.rollbacks);
        assertTrue(f.tx.committed.contains("status:DISPUTED:SETTLED"));
        assertTrue(f.tx.committed.contains("credit:2001:1900"));
    }

    @Test
    void arbitration_to_runner_rolls_back_every_failed_cas() {
        for (String failed : List.of("casEscrowStatus", "casSettleFromDispute", "debit:1:2000",
                "credit:2001:1900", "credit:2:100")) {
            Fixture f = new Fixture(ErrandStatus.DISPUTED);
            f.failAt = failed;
            assertEquals(ArbitrateErrandUseCase.Result.CONFLICT,
                    f.arbitrate().arbitrate(ID, ArbitrateErrandUseCase.Favor.RUNNER, ARBITRATOR), failed);
            f.assertRolledBack();
        }
    }

    @Test
    void cancellation_keeps_status_and_refund_in_the_same_transaction() {
        Fixture f = new Fixture(ErrandStatus.PUBLISHED);
        assertEquals(RefundErrandUseCase.Result.REFUNDED, f.refund().cancelAndRefund(ID, PUBLISHER));
        assertEquals(List.of("casEscrowStatus", "casCancel", "status:PUBLISHED:CANCELLED",
                "debit:1:2000", "credit:1001:2000", "ledger:DEBIT:2000", "ledger:CREDIT:2000"),
                f.tx.committed);
    }

    @Test
    void cancellation_rolls_back_status_and_money_on_every_cas_failure() {
        for (String failed : List.of("casEscrowStatus", "casCancel", "debit:1:2000", "credit:1001:2000")) {
            Fixture f = new Fixture(ErrandStatus.PUBLISHED);
            f.failAt = failed;
            assertEquals(RefundErrandUseCase.Result.CONFLICT, f.refund().cancelAndRefund(ID, PUBLISHER), failed);
            f.assertRolledBack();
        }
    }

    @Test
    void arbitration_refund_rolls_back_earlier_cas_if_task_or_account_update_loses_race() {
        for (String failed : List.of("casEscrowStatus", "casRefundFromDispute", "debit:1:2000", "credit:1001:2000")) {
            Fixture f = new Fixture(ErrandStatus.DISPUTED);
            f.failAt = failed;
            assertEquals(ArbitrateErrandUseCase.Result.CONFLICT,
                    f.arbitrate().arbitrate(ID, ArbitrateErrandUseCase.Favor.PUBLISHER, ARBITRATOR), failed);
            f.assertRolledBack();
        }
    }

    @Test
    void cancellation_rejects_wrong_actor_or_an_accepted_task_before_writing() {
        Fixture wrongActor = new Fixture(ErrandStatus.PUBLISHED);
        assertCode(ErrorCode.NOT_PUBLISHER, () -> wrongActor.refund().cancelAndRefund(ID, RUNNER));
        wrongActor.assertUntouched();
        Fixture accepted = new Fixture(ErrandStatus.ACCEPTED);
        assertCode(ErrorCode.ILLEGAL_STATE_TRANSITION, () -> accepted.refund().cancelAndRefund(ID, PUBLISHER));
        accepted.assertUntouched();
    }

    private static void assertCode(ErrorCode code, Runnable action) {
        assertEquals(code, assertThrows(BizException.class, action::run).code());
    }

    private static final class Fixture {
        final ErrandStatus status;
        final RecordingTransactionManager tx = new RecordingTransactionManager();
        final SnowflakeIdGenerator ids = new SnowflakeIdGenerator(1);
        String failAt;
        int walletReads;
        int errandReads;
        final ErrandRepository errands;
        final WalletRepository wallets;
        final CreditRepository credit;
        final RealtimeNotifier notifier = noop(RealtimeNotifier.class);
        final FundAuditPort audit = noop(FundAuditPort.class);
        final FundEventPort fund = (event, work) -> work.execute();
        final CacheEvictSupport cache = new CacheEvictSupport(noop(ErrandCachePort.class), null, 0, false, "AFTER_COMMIT");

        Fixture(ErrandStatus status) {
            this.status = status;
            errands = stub(ErrandRepository.class, (method, args) -> {
                if (method.equals("findById")) {
                    errandReads++;
                    return Optional.of(Errand.rehydrate(ID, 1, PUBLISHER, ErrandType.DELIVERY, "验收",
                            Money.ofCents(2000), 1, RUNNER, status, 1, 0, 7, null));
                }
                if (method.equals("appendStatusLog")) {
                    tx.write("status:" + args[1] + ":" + args[2]);
                    return null;
                }
                if (method.startsWith("cas")) return cas(method);
                throw new AssertionError("意外任务仓储调用：" + method);
            });
            wallets = stub(WalletRepository.class, (method, args) -> {
                if (method.equals("findEscrowByErrandId")) {
                    walletReads++;
                    return Optional.of(EscrowOrder.held(8001, 1, ID, PUBLISHER, Money.ofCents(2000)));
                }
                if (method.equals("findByOwner")) {
                    walletReads++;
                    String owner = (String) args[0];
                    long id = "-1".equals(owner) ? 1 : "-2".equals(owner) ? 2 : Long.parseLong(owner);
                    return Optional.of(new WalletAccount(id, owner, "", (AccountType) args[1],
                            Money.ofCents("-1".equals(owner) ? 2000 : 5000), Money.ofCents(0), 0));
                }
                if (method.equals("casEscrowStatus")) return cas(method);
                if (method.equals("casDebit") || method.equals("casCredit")) {
                    return cas((method.equals("casDebit") ? "debit:" : "credit:") + args[0] + ":" + ((Money) args[1]).cents());
                }
                if (method.equals("insertLedger")) {
                    LedgerEntry entry = (LedgerEntry) args[0];
                    tx.write("ledger:" + entry.direction() + ":" + entry.amount().cents());
                    return null;
                }
                throw new AssertionError("意外钱包仓储调用：" + method);
            });
            credit = stub(CreditRepository.class, (method, args) -> {
                if (method.equals("applyEvent")) { tx.write("creditEvent"); return true; }
                if (method.equals("scoreOf")) return 62;
                throw new AssertionError("意外信用仓储调用：" + method);
            });
        }

        int cas(String operation) {
            tx.write(operation);
            return operation.equals(failAt) ? 0 : 1;
        }

        /** 本测试不涉及优惠券：所有查询返回空，写入为 no-op */
        PlatformConfigPort config() {
            return new PlatformConfigPort() {
                @Override public double commissionRate() { return 0.05; }
                @Override public double voucherRunnerBearRatio() { return 0.35; }
                @Override public void update(double commissionRate, double voucherRunnerBearRatio) { }
            };
        }

        VoucherRepository vouchers() {
            return stub(VoucherRepository.class, (method, args) -> {
                if (method.equals("findUseByErrand")) return Optional.empty();
                if (method.equals("markUsedByErrand") || method.equals("restoreByErrand")) return 0;
                throw new AssertionError("意外优惠券仓储调用：" + method);
            });
        }

        SettleErrandUseCase settle() {
            return new SettleErrandUseCase(errands, wallets, audit, fund, ids, cache, credit,
                    noop(CreditRankingPort.class), new TransactionTemplate(tx), notifier, vouchers(), config());
        }

        RefundErrandUseCase refund() {
            return new RefundErrandUseCase(errands, wallets, audit, fund, ids, cache,
                    new TransactionTemplate(tx), notifier, vouchers());
        }

        ArbitrateErrandUseCase arbitrate() {
            ArbitrateSettleStep target = new ArbitrateSettleStep(errands, wallets, ids, credit, vouchers(), config());
            ProxyFactory proxy = new ProxyFactory(target);
            proxy.addAdvice(new TransactionInterceptor(tx, new AnnotationTransactionAttributeSource()));
            return new ArbitrateErrandUseCase(errands, wallets, refund(), (ArbitrateSettleStep) proxy.getProxy(),
                    audit, fund, cache, credit, ids, notifier,
                    stub(UserRepository.class, (method, args) -> Optional.empty()), ARBITRATOR);
        }

        void assertUntouched() {
            assertEquals(0, tx.commits);
            assertEquals(0, tx.rollbacks);
            assertTrue(tx.committed.isEmpty());
            assertTrue(tx.pending.isEmpty());
        }

        void assertRolledBack() {
            assertEquals(0, tx.commits, failAt);
            assertEquals(1, tx.rollbacks, failAt);
            assertTrue(tx.committed.isEmpty(), failAt);
            assertTrue(tx.pending.isEmpty(), failAt);
        }
    }

    /** 每次写入必须在真实 Spring 事务范围内，提交与回滚由事务管理器决定。 */
    private static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        final List<String> pending = new ArrayList<>();
        final List<String> committed = new ArrayList<>();
        boolean active;
        int commits;
        int rollbacks;

        void write(String operation) {
            assertTrue(active, "禁止事务外写入：" + operation);
            pending.add(operation);
        }

        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { active = true; }
        @Override protected void doCommit(DefaultTransactionStatus status) {
            commits++;
            committed.addAll(pending);
            pending.clear();
            active = false;
        }
        @Override protected void doRollback(DefaultTransactionStatus status) {
            rollbacks++;
            pending.clear();
            active = false;
        }
    }

    private static <T> T noop(Class<T> type) { return stub(type, (method, args) -> null); }

    private static <T> T stub(Class<T> type, BiFunction<String, Object[], Object> call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> call.apply(method.getName(), args)));
    }
}
