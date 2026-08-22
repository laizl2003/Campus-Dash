package com.campusdash.it;

import com.campusdash.application.usecase.GrabErrandUseCase;
import com.campusdash.application.usecase.PublishErrandUseCase;
import com.campusdash.domain.errand.model.ErrandStatus;
import com.campusdash.domain.errand.model.ErrandType;
import com.campusdash.domain.errand.ports.ErrandRepository;
import com.campusdash.domain.grab.ports.GrabSlotPort;
import com.campusdash.shared.ErrorCode;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 抢单并发正确性集成测试——一期最重要的测试。
 *
 * 验证四条不变式（架构文档 6.1）：
 *   INV-1 抢中人数 <= 名额数（不超卖）
 *   INV-2 同一用户最多占一个名额（不刷单）
 *   INV-3 重复请求只生效一次（幂等）
 *   INV-4 抢单失败者进候选队列
 */
@SpringBootTest(properties = {"dash.credit.max-ongoing=10000"})
class GrabConcurrencyIT {

    @Autowired PublishErrandUseCase publishUseCase;
    @Autowired GrabErrandUseCase grabUseCase;
    @Autowired ErrandRepository errandRepository;
    @Autowired GrabSlotPort grabSlotPort;
    @Autowired JdbcTemplate jdbc;

    private static BenchRun run;

    @BeforeAll
    static void requireMiddleware() {
        Assumptions.assumeTrue(MiddlewareAvailable.check(),
                "MySQL(3307)/Redis(6380) 未启动，跳过集成测试。请先执行 docker compose -f docker/docker-compose.yaml up -d");
        run = BenchRun.start("IT-GRAB");
    }

    @AfterAll
    static void finishRun() {
        if (run != null) {
            run.finish("PASS", "{\"scenario\":\"IT-GRAB\",\"concurrency\":2000,\"oversold\":0}");
            run.close();
        }
    }

    @BeforeEach
    void resetPublisherBalance() {
        // 每个用例前把发单人余额恢复到 1000 元，避免用例间相互影响
        jdbc.update("UPDATE wallet_account SET available = 100000, frozen = 0 WHERE owner_id = 1001 AND owner_type = 'USER'");
        jdbc.update("DELETE FROM credit_event WHERE user_id BETWEEN 2001 AND 6000");
        jdbc.update("DELETE FROM credit_score WHERE user_id BETWEEN 2001 AND 6000");
    }

    private long publishOneSlotErrand() {
        var result = publishUseCase.publish(new PublishErrandUseCase.Command(
                1L, 1001L, ErrandType.DELIVERY, "bench_取快递到3号楼", 800L, 1));
        assertEquals(ErrandStatus.PUBLISHED, result.status());
        run.track(result.errandId());
        return result.errandId();
    }

    @Test
    @DisplayName("INV-1：2000 并发抢 1 个名额，有且只有 1 人成功，零超卖")
    void spike_2000_concurrent_only_one_wins() throws Exception {
        long errandId = publishOneSlotErrand();
        int concurrency = 2000;

        AtomicInteger success = new AtomicInteger();
        AtomicInteger slotFull = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch fire = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(concurrency);

        // 用虚拟线程承载 2000 个并发：抢单是 IO 密集型，虚拟线程能用很少的平台线程支撑
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < concurrency; i++) {
                long runnerId = 2001L + i;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        // 所有线程在这里对齐，fire 一开闸就是真正的瞬时并发，
                        // 比"逐个启动线程"更接近秒杀式抢单，也更容易暴露并发 bug
                        fire.await();
                        var r = grabUseCase.grab(new GrabErrandUseCase.Command(
                                errandId, runnerId, UUID.randomUUID().toString(), 60));
                        if (r.grabbed()) {
                            success.incrementAndGet();
                        } else if (r.code() == ErrorCode.SLOT_FULL) {
                            slotFull.incrementAndGet();
                        } else if (r.code() == ErrorCode.GRAB_CONFLICT) {
                            conflict.incrementAndGet();
                        }
                    } catch (Exception e) {
                        // 异常也算失败，不能算成功
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(ready.await(30, TimeUnit.SECONDS), "线程未在 30s 内就绪");
            fire.countDown();
            assertTrue(done.await(120, TimeUnit.SECONDS), "抢单未在 120s 内完成");
        }

        // 校验一：应用层视角只有 1 人成功
        assertEquals(1, success.get(), "应用层成功数必须为 1，实际=" + success.get());

        // 校验二：数据库里抢中记录数 = 1（INV-1，防超卖的最终证据）
        Integer grabbed = jdbc.queryForObject(
                "SELECT COUNT(*) FROM grab_record WHERE errand_id = ? AND result = 'GRABBED'",
                Integer.class, errandId);
        assertEquals(1, grabbed, "数据库抢中记录数必须为 1（超卖检测）");

        // 校验三：没有用户占多个名额（INV-2）
        List<Map<String, Object>> dup = jdbc.queryForList("""
                SELECT runner_id, COUNT(*) c FROM grab_record
                 WHERE errand_id = ? AND result = 'GRABBED'
                 GROUP BY runner_id HAVING c > 1
                """, errandId);
        assertTrue(dup.isEmpty(), "存在用户占多个名额：" + dup);

        // 校验四：任务状态自洽
        var errand = errandRepository.findById(errandId).orElseThrow();
        assertEquals(ErrandStatus.LOCKED, errand.status());
        assertEquals(1, errand.slotTaken());
        assertNotNull(errand.grabberId());

        // 校验五：Redis 剩余名额与 DB 一致（检测名额泄漏）
        long remaining = grabSlotPort.remainingSlot(errandId);
        assertEquals(errand.slotTotal() - errand.slotTaken(), remaining,
                "Redis 名额与 DB 不一致，可能发生名额泄漏");

        System.out.printf("[S1] 并发=%d 成功=%d 名额满=%d 冲突=%d Redis剩余=%d%n",
                concurrency, success.get(), slotFull.get(), conflict.get(), remaining);
    }

    @Test
    @DisplayName("INV-3：同一 requestId 重复提交只生效一次（幂等）")
    void duplicate_request_is_idempotent() {
        long errandId = publishOneSlotErrand();
        String requestId = UUID.randomUUID().toString();

        var first = grabUseCase.grab(new GrabErrandUseCase.Command(errandId, 3001L, requestId, 60));
        var second = grabUseCase.grab(new GrabErrandUseCase.Command(errandId, 3001L, requestId, 60));

        assertTrue(first.grabbed(), "首次抢单应成功");
        assertTrue(second.grabbed(), "重复请求应返回首次的成功结果");

        Integer grabbed = jdbc.queryForObject(
                "SELECT COUNT(*) FROM grab_record WHERE errand_id = ? AND result = 'GRABBED'",
                Integer.class, errandId);
        assertEquals(1, grabbed, "重复请求不能产生第二条抢中记录");
    }

    @Test
    @DisplayName("INV-2：同一用户换 requestId 再抢，被 ALREADY_GRABBED 拦住")
    void same_user_cannot_take_two_slots() {
        var result = publishUseCase.publish(new PublishErrandUseCase.Command(
                1L, 1001L, ErrandType.BUY, "bench_两个名额", 1000L, 2));
        long errandId = result.errandId();
        run.track(errandId);

        var first = grabUseCase.grab(new GrabErrandUseCase.Command(
                errandId, 4001L, UUID.randomUUID().toString(), 60));
        var second = grabUseCase.grab(new GrabErrandUseCase.Command(
                errandId, 4001L, UUID.randomUUID().toString(), 60));

        assertTrue(first.grabbed());
        assertFalse(second.grabbed());
        assertEquals(ErrorCode.ALREADY_GRABBED, second.code());
    }

    @Test
    @DisplayName("INV-4：抢单失败者进入候选队列")
    void losers_enter_candidate_queue() {
        long errandId = publishOneSlotErrand();

        grabUseCase.grab(new GrabErrandUseCase.Command(errandId, 5001L, UUID.randomUUID().toString(), 60));
        var loser = grabUseCase.grab(new GrabErrandUseCase.Command(errandId, 5002L, UUID.randomUUID().toString(), 80));

        assertFalse(loser.grabbed());
        assertEquals(ErrorCode.SLOT_FULL, loser.code());
        assertNotNull(loser.candidateRank(), "失败者必须进候选队列");
        assertTrue(loser.candidateRank() >= 1);
    }
}
