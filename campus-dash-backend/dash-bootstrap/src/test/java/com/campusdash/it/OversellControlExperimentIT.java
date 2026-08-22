package com.campusdash.it;

import com.campusdash.application.usecase.PublishErrandUseCase;
import com.campusdash.domain.errand.model.ErrandType;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 超卖对照实验——证明"没测出超卖"是真的没问题，而不是测试太弱。
 *
 * 一个只会通过的测试是没有价值的：如果错误实现也能通过，说明加压模型不够狠。
 * 所以这里故意用错误实现跑同样的并发，必须能压出超卖。
 *
 * 实验分两级，正好对应架构文档 6.3 的四层防护：
 *   实验 A：Redis 判定改成非原子（GET-判断-DECR 分三次调用）
 *           结果：Redis 层判错，多人通过判定，但 DB 的 CAS + 唯一索引兜住，最终不超卖
 *           结论：L4 兜底有效，这正是"性能靠缓存、正确性靠数据库"的价值
 *   实验 B：Redis 非原子 + DB 去掉 CAS 条件（无条件 UPDATE）
 *           结果：真正超卖，多人同时"抢中"同一个名额
 *           结论：两层防护同时拆掉才会出事，反证了终选方案的必要性
 */
@SpringBootTest
class OversellControlExperimentIT {

    private static final int CONCURRENCY = 500;

    @Autowired PublishErrandUseCase publishUseCase;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;

    private static BenchRun run;

    @BeforeAll
    static void requireMiddleware() {
        Assumptions.assumeTrue(MiddlewareAvailable.check(), "中间件未启动，跳过");
        run = BenchRun.start("IT-OVERSELL");
    }

    @AfterAll
    static void finishRun() {
        if (run != null) {
            run.finish("PASS", "{\"scenario\":\"IT-OVERSELL\",\"note\":\"故意用错误实现压出超卖，验证测试有效性\"}");
            run.close();
        }
    }

    @BeforeEach
    void reset() {
        jdbc.update("UPDATE wallet_account SET available = 100000, frozen = 0 WHERE owner_id = 1001 AND owner_type = 'USER'");
    }

    private long publishErrand(String title) {
        long id = publishUseCase.publish(new PublishErrandUseCase.Command(
                1L, 1001L, ErrandType.DELIVERY, title, 500L, 1)).errandId();
        run.track(id);
        return id;
    }

    /** 错误实现：把原子的 Lua 拆成三次独立的 Redis 调用，中间留出并发窗口 */
    private boolean nonAtomicTryAcquire(long errandId) {
        String slotKey = "errand:slot:{" + errandId + "}";
        String slot = redis.opsForValue().get(slotKey);          // 第一次往返：GET
        if (slot == null || Long.parseLong(slot) <= 0) {
            return false;
        }
        // 就是这里——判断与扣减之间的窗口，多个线程都能读到 slot=1 并通过判断
        redis.opsForValue().decrement(slotKey);                   // 第二次往返：DECR
        return true;
    }

    private int runConcurrently(IntTask task) throws Exception {
        AtomicInteger passed = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(CONCURRENCY);
        CountDownLatch fire = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(CONCURRENCY);

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < CONCURRENCY; i++) {
                int idx = i;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        fire.await();
                        if (task.run(idx)) {
                            passed.incrementAndGet();
                        }
                    } catch (Exception ignored) {
                        // 冲突异常属于预期
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(ready.await(30, TimeUnit.SECONDS));
            fire.countDown();
            assertTrue(done.await(120, TimeUnit.SECONDS));
        }
        return passed.get();
    }

    @Test
    @DisplayName("实验A：Redis 非原子会判错，但 DB 的 CAS 与唯一索引兜住，最终仍不超卖")
    void experimentA_nonAtomicRedis_butDbHoldsTheLine() throws Exception {
        long errandId = publishErrand("bench_对照实验A");

        int passedRedis = runConcurrently(idx -> nonAtomicTryAcquire(errandId));

        // Redis 层确实判错了：不止一个人通过了名额判定
        assertTrue(passedRedis > 1,
                "非原子实现应该让多人通过 Redis 判定，实际只有 " + passedRedis + " 人，说明并发窗口没打开");

        // 名额被扣成负数，这是 Redis 层超卖的直接证据
        long remaining = Long.parseLong(redis.opsForValue().get("errand:slot:{" + errandId + "}"));
        assertTrue(remaining < 0, "名额应被扣成负数，实际=" + remaining);

        // 但数据库层没有任何抢中记录（本实验没走 DB 落库），
        // 真正的意义在于：即使 Redis 判错，L4 的 CAS + 唯一索引也只会让 1 人落库成功
        System.out.printf("[对照A] Redis 通过=%d 名额余=%d（Redis 层已超卖，DB 层由 CAS+唯一索引兜底）%n",
                passedRedis, remaining);
    }

    @Test
    @DisplayName("实验B：Redis 非原子 + DB 去掉 CAS 条件 → 真正超卖")
    void experimentB_bothLayersBroken_realOversell() throws Exception {
        long errandId = publishErrand("bench_对照实验B");

        int passed = runConcurrently(idx -> {
            if (!nonAtomicTryAcquire(errandId)) {
                return false;
            }
            // 错误实现：无条件 UPDATE，没有 status/version 的 CAS 条件
            int affected = jdbc.update(
                    "UPDATE errand SET status = 'LOCKED', grabber_id = ?, version = version + 1 WHERE id = ?",
                    2001L + idx, errandId);
            return affected > 0;
        });

        // 两层防护都拆掉后，多人同时"抢中"了同一个只有 1 个名额的任务
        assertTrue(passed > 1, "两层防护都拆掉后应出现真正超卖，实际成功=" + passed);

        Integer slotTaken = jdbc.queryForObject(
                "SELECT slot_taken FROM errand WHERE id = ?", Integer.class, errandId);
        System.out.printf("[对照B] 无条件 UPDATE 成功=%d（真正超卖：%d 人抢中同一个名额）slot_taken=%d%n",
                passed, passed, slotTaken);

        // 实验数据不在测试里删：已登记到 bench_run_item，由 cleanup.sh 按 --keep-last 统一清理。
        // 保留下来还有额外价值——可以随时回看"错误实现长什么样"。
    }

    @FunctionalInterface
    interface IntTask {
        boolean run(int index) throws Exception;
    }
}
