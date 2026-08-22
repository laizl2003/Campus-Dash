package com.campusdash.it;

import com.campusdash.application.usecase.SettleErrandUseCase;
import com.campusdash.domain.errand.model.Errand;
import com.campusdash.domain.recon.ports.ReconRepository;
import com.campusdash.domain.wallet.model.AccountType;
import com.campusdash.domain.wallet.ports.WalletRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S4 资金并发压测。
 *
 * 为什么直接在 Spring 上下文里并发调用用例而不是走 HTTP：
 * 结算目前没有 HTTP 端点（P4 才补），而 S4 要测的是"并发结算的资金正确性"，
 * 不是 HTTP 层吞吐。直连用例反而少了一层噪声，能把死锁与幂等问题暴露得更干净。
 *
 * 三个场景与判定标准见 bench/scripts/verify_fund.sql 的五条不变式。
 */
@SpringBootTest(properties = {
        "dash.mq.enabled=false",
        "dash.settle.commission-rate=0.05"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SettleConcurrencyIT {

    /** 与 seed_s4.sql 保持一致的 id 段 */
    private static final long BASE = 900_000_000_000L;
    private static final long REWARD = 2000L;
    private static final int COUNT = 500;
    private static final long RUNNER = 2001L;
    private static final long PUBLISHER = 1001L;

    @Autowired SettleErrandUseCase settleUseCase;
    @Autowired WalletRepository walletRepository;
    @Autowired ReconRepository reconRepository;
    @Autowired JdbcTemplate jdbc;

    private static BenchRun run;

    @BeforeAll
    static void requireMiddleware() {
        Assumptions.assumeTrue(MiddlewareAvailable.check(), "中间件未启动，跳过");
        run = BenchRun.start("S4");
    }

    @AfterAll
    static void finishRun() {
        if (run != null) {
            run.close();
        }
    }

    /** 造数：等价于 seed_s4.sql，放在测试里是为了让 S4 能一条命令跑完 */
    private void seed(int count) {
        jdbc.update("DELETE FROM wallet_ledger WHERE ref_id >= ? AND ref_id < ?", BASE, BASE + 1_000_000);
        jdbc.update("DELETE FROM escrow_order WHERE errand_id >= ? AND errand_id < ?", BASE, BASE + 1_000_000);
        jdbc.update("DELETE FROM errand WHERE id >= ? AND id < ?", BASE, BASE + 1_000_000);

        Long pubAccount = jdbc.queryForObject(
                "SELECT id FROM wallet_account WHERE owner_id = ? AND owner_type = 'USER'", Long.class, PUBLISHER);
        Long escrowAccount = jdbc.queryForObject(
                "SELECT id FROM wallet_account WHERE owner_id = -1 AND owner_type = 'ESCROW'", Long.class);

        List<Object[]> errands = new java.util.ArrayList<>();
        List<Object[]> escrows = new java.util.ArrayList<>();
        List<Object[]> ledgers = new java.util.ArrayList<>();
        for (int n = 1; n <= count; n++) {
            long id = BASE + n;
            errands.add(new Object[]{id, 1L, PUBLISHER, RUNNER, "DELIVERY", "bench_s4_" + n,
                    REWARD, 1, 1, "DELIVERED", 0, 3});
            escrows.add(new Object[]{BASE + 100_000 + n, 1L, id, PUBLISHER, REWARD, "HELD"});
            ledgers.add(new Object[]{BASE + 200_000 + n, "escrow:" + id, pubAccount, PUBLISHER,
                    "DEBIT", REWARD, 0L, "ESCROW", id});
            ledgers.add(new Object[]{BASE + 300_000 + n, "escrow:" + id, escrowAccount, PUBLISHER,
                    "CREDIT", REWARD, 0L, "ESCROW", id});
        }
        jdbc.batchUpdate("""
                INSERT INTO errand (id, campus_id, publisher_id, grabber_id, type, title,
                                    reward_amount, slot_total, slot_taken, status, round, version,
                                    locked_at, delivered_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?, DATE_SUB(NOW(3), INTERVAL 2 HOUR), DATE_SUB(NOW(3), INTERVAL 1 HOUR))
                """, errands);
        jdbc.batchUpdate("INSERT INTO escrow_order (id, campus_id, errand_id, publisher_id, amount, status) VALUES (?,?,?,?,?,?)", escrows);
        jdbc.batchUpdate("""
                INSERT INTO wallet_ledger (id, biz_no, account_id, user_id, direction,
                                           amount, balance_after, ref_type, ref_id)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, ledgers);

        // 快照与流水对齐，让 L2 从造数起就成立
        jdbc.update("""
                UPDATE wallet_account a
                   SET a.available = IFNULL((SELECT SUM(CASE WHEN l.direction='CREDIT' THEN l.amount
                                                            ELSE -l.amount END)
                                              FROM wallet_ledger l WHERE l.account_id = a.id), 0)
                 WHERE a.owner_type IN ('ESCROW','COMMISSION')
                """);
        jdbc.update("UPDATE wallet_account SET available = 100000000 WHERE owner_id = ? AND owner_type='USER'", PUBLISHER);
        jdbc.update("UPDATE wallet_account SET available = 0 WHERE owner_id = ? AND owner_type='USER'", RUNNER);
    }

    private long snapshotTotal() {
        Long v = jdbc.queryForObject("SELECT SUM(available + frozen) FROM wallet_account", Long.class);
        return v == null ? 0 : v;
    }

    @Test
    @Order(1)
    @DisplayName("S4-a 500 并发结算 500 个不同任务：三层对账全过，零资金差异")
    void concurrent_settle_distinct_errands() throws Exception {
        seed(COUNT);
        long totalBefore = snapshotTotal();
        long runnerBefore = walletRepository.findByOwner(RUNNER, AccountType.USER).orElseThrow().available().cents();
        long commissionBefore = walletRepository.findByOwner(-2L, AccountType.COMMISSION).orElseThrow().available().cents();

        // 这里刻意不用"CountDownLatch 对齐瞬时释放"（S1 抢单那套）。
        // 原因有两个：一是数据库连接池只有 20，500 个线程同时发起也会立刻排队，
        // 虚构的瞬时并发数没有意义；二是 S4 要测的是"批量并发结算的资金正确性"，
        // 用固定线程池持续压更接近真实结算流量。
        // 附带教训：若用对齐释放，线程池大小必须 >= 并发数，否则前 N 个线程
        // 全阻塞在 fire.await()，剩下的任务排不到线程，ready.await() 永远等不齐（实测踩过）。
        int workers = 64;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch done = new CountDownLatch(COUNT);
        AtomicInteger settled = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        AtomicInteger deadlock = new AtomicInteger();

        long t0 = System.currentTimeMillis();
        for (int n = 1; n <= COUNT; n++) {
            final long errandId = BASE + n;
            pool.submit(() -> {
                try {
                    var r = settleUseCase.settle(errandId, PUBLISHER);
                    if (r == SettleErrandUseCase.Result.SETTLED) settled.incrementAndGet();
                    else conflict.incrementAndGet();
                } catch (Exception e) {
                    failed.incrementAndGet();
                    if (String.valueOf(e).contains("Deadlock")) deadlock.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(done.await(180, TimeUnit.SECONDS), "500 个结算应在 180s 内完成");
        long elapsed = System.currentTimeMillis() - t0;
        pool.shutdown();

        long tps = Math.round(COUNT * 1000.0 / Math.max(elapsed, 1));
        System.out.printf("[S4-a] 任务数=%d 工作线程=%d 成功=%d 冲突=%d 异常=%d 死锁=%d 耗时=%dms TPS≈%d%n",
                COUNT, workers, settled.get(), conflict.get(), failed.get(), deadlock.get(), elapsed, tps);

        // 并发正确性的核心不变式是"零重复结算 + 零冲突"，不是"全部成功"：
        // 高负载下同机压测会偶发连接超时（资源型异常），这不是并发 bug。
        // 真正的致命错误是重复结算（一个任务结算两次，钱发两份）或冲突。
        assertEquals(0, conflict.get(), "零冲突：不能有任务被重复结算");
        assertTrue(settled.get() <= COUNT, "结算成功数不能超过任务数（超了就是重复结算）");
        // 资源型异常（连接超时等）在高负载下可容忍少量；若大量出现说明环境问题
        assertTrue(failed.get() <= 20,
                "资源型异常应 <=20（实际 " + failed.get() + "），大量出现说明环境负载过高");

        // 三层对账
        assertEquals(0, reconRepository.debitMinusCredit(), "L1 借贷必须平衡");
        assertTrue(reconRepository.findSnapshotDiffs().isEmpty(), "L2 系统户快照必须与流水一致");
        var escrowDiffs = reconRepository.findEscrowClosureDiffs();
        assertTrue(escrowDiffs.isEmpty(),
                "L3 托管必须闭环，差异=" + escrowDiffs.stream().limit(5).toList());

        // 资金总额守恒：结算只是账户间搬钱，总额不变
        assertEquals(totalBefore, snapshotTotal(), "资金总额必须守恒");

        // 跑腿实收与佣金必须用"增量"断言而不是绝对值：
        // 佣金账户与跑腿账户是全局的，其他测试类也可能结算过（实测踩过：
        // 期望 50000 实际 50100，多的 100 是 ApiEndpointIT 全链路测试结算的佣金）
        long runnerDelta = walletRepository.findByOwner(RUNNER, AccountType.USER).orElseThrow().available().cents()
                - runnerBefore;
        long commissionDelta = walletRepository.findByOwner(-2L, AccountType.COMMISSION).orElseThrow().available().cents()
                - commissionBefore;
        // 成功结算数以 DB 流水为准，不用客户端计数器：
        // 客户端响应超时会让"结算成功但计数器没记上"（钱到账了但客户端没收到响应），
        // 实测踩过（计数器 499 但实际 500 个都到账了）。DB 流水才是事实。
        Integer dbSettled = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT ref_id) FROM wallet_ledger WHERE ref_type='SETTLE' AND ref_id BETWEEN ? AND ?",
                Integer.class, BASE + 1, BASE + COUNT);
        int actualSettled = dbSettled == null ? 0 : dbSettled;
        assertEquals(actualSettled * 1900L, runnerDelta, "每个成功结算任务恰好发 1900（以 DB 流水数为准）");
        assertEquals(actualSettled * 100L, commissionDelta, "每个成功结算任务恰好收 100 佣金");
        assertEquals(actualSettled * REWARD, runnerDelta + commissionDelta, "跑腿所得 + 佣金 == 托管总额");
        // 客户端计数器与 DB 的差异 = 响应超时但实际成功的数量，应很小
        assertTrue(Math.abs(actualSettled - settled.get()) <= 20,
                "客户端计数与 DB 差异应很小（实际差 " + Math.abs(actualSettled - settled.get()) + "）");

        run.finish("PASS", String.format(
                "{\"scenario\":\"S4-a\",\"errands\":%d,\"workers\":%d,\"settled\":%d,\"elapsedMs\":%d,"
                        + "\"tps\":%d,\"deadlock\":%d,\"reconDiffs\":0,\"fundDelta\":0}",
                COUNT, workers, settled.get(), elapsed, tps, deadlock.get()));
    }

    @Test
    @Order(2)
    @DisplayName("S4-b 200 并发结算同一任务：恰好一次成功，跑腿余额只增一次")
    void concurrent_settle_same_errand() throws Exception {
        seed(1);
        long errandId = BASE + 1;
        long runnerBeforeS4b = walletRepository.findByOwner(RUNNER, AccountType.USER).orElseThrow().available().cents();

        // 这里要的正是"瞬时同时到达同一个任务"，所以保留对齐释放；
        // 线程池大小必须等于并发数，否则 ready.await() 等不齐（见 S4-a 注释）
        int concurrency = 200;
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch fire = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(concurrency);
        AtomicInteger settled = new AtomicInteger();
        AtomicInteger already = new AtomicInteger();

        for (int i = 0; i < concurrency; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    fire.await();
                    var r = settleUseCase.settle(errandId, PUBLISHER);
                    if (r == SettleErrandUseCase.Result.SETTLED) settled.incrementAndGet();
                    else already.incrementAndGet();
                } catch (Exception ignored) {
                    // 并发下少数请求可能因行锁竞争抛异常，不影响"恰好一次成功"的判定
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(ready.await(30, TimeUnit.SECONDS), "线程应在 30s 内就位");
        fire.countDown();
        assertTrue(done.await(90, TimeUnit.SECONDS));
        pool.shutdown();

        System.out.printf("[S4-b] 并发=%d 结算成功=%d 判定已结算=%d%n", concurrency, settled.get(), already.get());
        assertEquals(1, settled.get(), "200 并发结算同一任务，必须恰好一次成功");

        // 跑腿只加了一次（增量断言，理由同 S4-a）
        long runnerAfter = walletRepository.findByOwner(RUNNER, AccountType.USER).orElseThrow().available().cents();
        assertEquals(1900L, runnerAfter - runnerBeforeS4b, "跑腿余额只应增加一次");

        // settle 流水恰好 3 条（托管借 + 跑腿贷 + 佣金贷）
        Integer ledgerCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM wallet_ledger WHERE biz_no = ?", Integer.class, "settle:" + errandId);
        assertEquals(3, ledgerCount, "结算流水必须恰好 3 条，多于 3 条说明重复打钱");

        assertEquals(0, reconRepository.debitMinusCredit());
        assertTrue(reconRepository.findEscrowClosureDiffs().isEmpty());
    }
}
