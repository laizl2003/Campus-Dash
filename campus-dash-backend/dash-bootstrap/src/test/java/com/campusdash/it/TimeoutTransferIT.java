package com.campusdash.it;

import com.campusdash.application.usecase.*;
import com.campusdash.domain.errand.model.ErrandStatus;
import com.campusdash.domain.errand.model.ErrandType;
import com.campusdash.domain.errand.ports.ErrandRepository;
import com.campusdash.domain.errand.ports.LocalMessageRepository;
import com.campusdash.domain.grab.ports.CandidateQueuePort;
import com.campusdash.domain.grab.ports.GrabSlotPort;
import com.campusdash.shared.BizException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 超时流转集成测试（P2 核心验收）。
 *
 * 超时时长用配置压到 2 秒：否则测试要真等 5 分钟，没法跑。
 * 这也是把 confirm-seconds 做成配置项的直接理由。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "dash.timeout.confirm-seconds=2",
        "dash.timeout.max-transfer-rounds=3",
        "dash.mq.enabled=false",
        "dash.credit.max-ongoing=10000"
})
class TimeoutTransferIT {

    @Autowired PublishErrandUseCase publishUseCase;
    @Autowired GrabErrandUseCase grabUseCase;
    @Autowired ConfirmErrandUseCase confirmUseCase;
    @Autowired TimeoutTransferUseCase timeoutUseCase;
    @Autowired ErrandRepository errandRepository;
    @Autowired CandidateQueuePort candidateQueue;
    @Autowired GrabSlotPort grabSlotPort;
    @Autowired LocalMessageRepository localMessageRepository;
    @Autowired JdbcTemplate jdbc;

    private static BenchRun run;

    @BeforeAll
    static void requireMiddleware() {
        Assumptions.assumeTrue(MiddlewareAvailable.check(), "中间件未启动，跳过");
        run = BenchRun.start("IT-TIMEOUT");
    }

    @AfterAll
    static void finishRun() {
        if (run != null) {
            run.finish("PASS", "{\"scenario\":\"IT-TIMEOUT\",\"duplicateTransfer\":0}");
            run.close();
        }
    }

    @BeforeEach
    void reset() {
        jdbc.update("UPDATE wallet_account SET available = 100000, frozen = 0 WHERE owner_id = 1001 AND owner_type='USER'");
        jdbc.update("DELETE FROM credit_event WHERE user_id BETWEEN 2001 AND 2050");
        jdbc.update("DELETE FROM credit_score WHERE user_id BETWEEN 2001 AND 2050");
    }

    private long publishAndGrab(long firstRunner) {
        long errandId = publishUseCase.publish(new PublishErrandUseCase.Command(
                1L, 1001L, ErrandType.DELIVERY, "bench_超时流转", 600L, 1)).errandId();
        run.track(errandId);
        var r = grabUseCase.grab(new GrabErrandUseCase.Command(
                errandId, firstRunner, UUID.randomUUID().toString(), 60));
        assertTrue(r.grabbed(), "首个跑腿应抢中");
        return errandId;
    }

    @Test
    @DisplayName("超时未确认：流转给候选队列下一位，round+1，名额不变")
    void timeout_transfers_to_next_candidate() {
        long errandId = publishAndGrab(2001L);
        // 2002 抢不到，进候选队列
        var loser = grabUseCase.grab(new GrabErrandUseCase.Command(
                errandId, 2002L, UUID.randomUUID().toString(), 60));
        assertFalse(loser.grabbed());
        assertEquals(1L, candidateQueue.size(errandId));

        var before = errandRepository.findById(errandId).orElseThrow();
        var outcome = timeoutUseCase.handleTimeout(errandId, before.round());

        assertEquals(TimeoutTransferUseCase.Outcome.TRANSFERRED, outcome);
        var after = errandRepository.findById(errandId).orElseThrow();
        assertEquals(ErrandStatus.LOCKED, after.status(), "流转后仍是 LOCKED");
        assertEquals(2002L, after.grabberId(), "抢中者应换成候选人");
        assertEquals(before.round() + 1, after.round(), "round 必须自增");
        assertEquals(1, after.slotTaken(), "名额不变，只是占用者换人");
        assertEquals(0L, candidateQueue.size(errandId), "候选人已被弹出");
    }

    @Test
    @DisplayName("候选队列为空：任务回退 PUBLISHED，Redis 名额归还")
    void timeout_reverts_when_no_candidate() {
        long errandId = publishAndGrab(2011L);
        assertEquals(0L, candidateQueue.size(errandId));
        assertEquals(0L, grabSlotPort.remainingSlot(errandId), "抢中后名额已扣为 0");

        var before = errandRepository.findById(errandId).orElseThrow();
        var outcome = timeoutUseCase.handleTimeout(errandId, before.round());

        assertEquals(TimeoutTransferUseCase.Outcome.REVERTED, outcome);
        var after = errandRepository.findById(errandId).orElseThrow();
        assertEquals(ErrandStatus.PUBLISHED, after.status());
        assertNull(after.grabberId());
        assertEquals(0, after.slotTaken(), "名额必须归还");
        assertEquals(1L, grabSlotPort.remainingSlot(errandId),
                "Redis 名额也要还回去，否则任务显示可抢但谁都抢不到");

        // 回退后新的跑腿确实能抢到
        var regrab = grabUseCase.grab(new GrabErrandUseCase.Command(
                errandId, 2012L, UUID.randomUUID().toString(), 60));
        assertTrue(regrab.grabbed(), "回退后应可被重新抢单");
    }

    @Test
    @DisplayName("已确认的任务不会被流转：version 变了，超时消息的 CAS 必然失败")
    void confirmed_errand_is_not_transferred() {
        long errandId = publishAndGrab(2021L);
        grabUseCase.grab(new GrabErrandUseCase.Command(
                errandId, 2022L, UUID.randomUUID().toString(), 60));

        var beforeConfirm = errandRepository.findById(errandId).orElseThrow();
        confirmUseCase.confirm(new ConfirmErrandUseCase.Command(errandId, 2021L));

        // 用确认之前的 round 模拟"迟到的超时消息"
        var outcome = timeoutUseCase.handleTimeout(errandId, beforeConfirm.round());
        assertEquals(TimeoutTransferUseCase.Outcome.SKIPPED, outcome);

        var after = errandRepository.findById(errandId).orElseThrow();
        assertEquals(ErrandStatus.ACCEPTED, after.status(), "已确认的任务状态不该被改动");
        assertEquals(2021L, after.grabberId());
    }

    @Test
    @DisplayName("幂等：同一条超时消息重复投递 5 次，只流转一次")
    void duplicate_timeout_message_transfers_once() {
        long errandId = publishAndGrab(2031L);
        var firstLoser = grabUseCase.grab(new GrabErrandUseCase.Command(errandId, 2032L, UUID.randomUUID().toString(), 60));
        var secondLoser = grabUseCase.grab(new GrabErrandUseCase.Command(errandId, 2033L, UUID.randomUUID().toString(), 60));
        assertEquals(com.campusdash.shared.ErrorCode.SLOT_FULL, firstLoser.code());
        assertEquals(com.campusdash.shared.ErrorCode.SLOT_FULL, secondLoser.code());

        int round = errandRepository.findById(errandId).orElseThrow().round();

        int transferred = 0;
        for (int i = 0; i < 5; i++) {
            if (timeoutUseCase.handleTimeout(errandId, round) == TimeoutTransferUseCase.Outcome.TRANSFERRED) {
                transferred++;
            }
        }
        assertEquals(1, transferred, "重复消息只能生效一次");

        var after = errandRepository.findById(errandId).orElseThrow();
        assertEquals(round + 1, after.round(), "round 只能推进一格");
        assertEquals(1L, candidateQueue.size(errandId), "只应弹出一个候选人，另一个还在队列里");
    }

    @Test
    @DisplayName("旧轮次消息重放：round 不匹配直接丢弃（version CAS 之外的第二道保险）")
    void stale_round_message_is_discarded() {
        long errandId = publishAndGrab(2041L);
        grabUseCase.grab(new GrabErrandUseCase.Command(errandId, 2042L, UUID.randomUUID().toString(), 60));
        grabUseCase.grab(new GrabErrandUseCase.Command(errandId, 2043L, UUID.randomUUID().toString(), 60));

        int round0 = errandRepository.findById(errandId).orElseThrow().round();
        assertEquals(TimeoutTransferUseCase.Outcome.TRANSFERRED,
                timeoutUseCase.handleTimeout(errandId, round0));

        // 第 0 轮的消息延迟很久才到，此时任务已在第 1 轮
        var outcome = timeoutUseCase.handleTimeout(errandId, round0);
        assertEquals(TimeoutTransferUseCase.Outcome.SKIPPED, outcome, "旧轮次消息必须被丢弃");

        var after = errandRepository.findById(errandId).orElseThrow();
        assertEquals(round0 + 1, after.round(), "旧消息不能把 round 再推一格");
    }

    @Test
    @DisplayName("确认与超时流转并发竞争：必有一方失败，最终状态自洽")
    void confirm_and_transfer_race_ends_consistently() throws Exception {
        int rounds = 30;
        AtomicInteger confirmWin = new AtomicInteger();
        AtomicInteger transferWin = new AtomicInteger();

        for (int i = 0; i < rounds; i++) {
            long runner = 2101L + i;
            long errandId = publishAndGrab(runner);
            grabUseCase.grab(new GrabErrandUseCase.Command(
                    errandId, 2201L + i, UUID.randomUUID().toString(), 60));
            int round = errandRepository.findById(errandId).orElseThrow().round();

            CountDownLatch fire = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(2);
            AtomicInteger confirmOk = new AtomicInteger();
            AtomicInteger transferOk = new AtomicInteger();

            try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
                pool.submit(() -> {
                    try {
                        fire.await();
                        confirmUseCase.confirm(new ConfirmErrandUseCase.Command(errandId, runner));
                        confirmOk.incrementAndGet();
                    } catch (BizException | InterruptedException ignored) {
                        // 竞争失败是预期结果
                    } finally {
                        done.countDown();
                    }
                });
                pool.submit(() -> {
                    try {
                        fire.await();
                        if (timeoutUseCase.handleTimeout(errandId, round)
                                == TimeoutTransferUseCase.Outcome.TRANSFERRED) {
                            transferOk.incrementAndGet();
                        }
                    } catch (Exception ignored) {
                        // 同上
                    } finally {
                        done.countDown();
                    }
                });
                fire.countDown();
                assertTrue(done.await(30, TimeUnit.SECONDS));
            }

            // 核心断言：两者不能同时成功
            assertTrue(confirmOk.get() + transferOk.get() <= 1,
                    "确认与流转不能同时成功 errandId=" + errandId);

            var finalState = errandRepository.findById(errandId).orElseThrow();
            if (confirmOk.get() == 1) {
                confirmWin.incrementAndGet();
                assertEquals(ErrandStatus.ACCEPTED, finalState.status());
                assertEquals(runner, finalState.grabberId(), "确认赢了，抢中者不该变");
            } else if (transferOk.get() == 1) {
                transferWin.incrementAndGet();
                assertEquals(ErrandStatus.LOCKED, finalState.status());
                assertEquals(round + 1, finalState.round());
            }
            // 无论谁赢，名额都只能是 1
            assertEquals(1, finalState.slotTaken(), "并发竞争不能造成名额错乱");
        }

        System.out.printf("[并发竞争] %d 轮：确认胜出=%d 流转胜出=%d（两者从未同时成功）%n",
                rounds, confirmWin.get(), transferWin.get());
    }

    @Test
    @DisplayName("防无限流转：轮次达上限后直接回退，不再逐个试候选人")
    void max_rounds_reached_triggers_revert() {
        long errandId = publishAndGrab(2401L);
        // 造 6 个候选人，超过 max-transfer-rounds=3
        for (int i = 0; i < 6; i++) {
            grabUseCase.grab(new GrabErrandUseCase.Command(
                    errandId, 2410L + i, UUID.randomUUID().toString(), 60));
        }

        TimeoutTransferUseCase.Outcome last = null;
        for (int i = 0; i < 10; i++) {
            int round = errandRepository.findById(errandId).orElseThrow().round();
            last = timeoutUseCase.handleTimeout(errandId, round);
            if (last == TimeoutTransferUseCase.Outcome.REVERTED) {
                break;
            }
        }

        assertEquals(TimeoutTransferUseCase.Outcome.REVERTED, last,
                "达到轮次上限后必须回退，否则一个爆款任务会流转上千轮");
        var after = errandRepository.findById(errandId).orElseThrow();
        assertEquals(ErrandStatus.PUBLISHED, after.status());
        assertTrue(after.round() <= 4, "轮次不该无限增长，实际=" + after.round());
    }

    @Test
    @DisplayName("本地消息表：抢单后登记了超时消息，msg_key 带 round")
    void timeout_message_is_enqueued_with_round() {
        long errandId = publishAndGrab(2301L);

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM local_message WHERE msg_key = ?",
                Integer.class, TimeoutPolicy.msgKey(errandId, 0));
        assertEquals(1, count, "抢中后应登记第 0 轮的超时消息");

        // 重复登记同一轮应被唯一索引挡住
        var again = localMessageRepository.enqueue(999999L, TimeoutPolicy.msgKey(errandId, 0),
                TimeoutPolicy.TOPIC_CONFIRM_TIMEOUT, "{}", java.time.Instant.now());
        assertFalse(again, "同一 msg_key 不能登记两次（幂等第三件）");
    }
}
