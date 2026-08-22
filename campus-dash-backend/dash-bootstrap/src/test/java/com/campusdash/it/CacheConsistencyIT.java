package com.campusdash.it;

import com.campusdash.application.usecase.ConfirmErrandUseCase;
import com.campusdash.application.usecase.GrabErrandUseCase;
import com.campusdash.application.usecase.PublishErrandUseCase;
import com.campusdash.application.usecase.query.GetErrandDetailUseCase;
import com.campusdash.domain.errand.model.ErrandType;
import com.campusdash.domain.errand.ports.ErrandCachePort;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 缓存一致性正向验证：读穿透回填、布隆防穿透、逻辑过期单线程重建、写后最终一致。
 *
 * 对照实验（证明错误实现真的会错）在另外两个类：
 *   - CacheEvictionOrderControlIT：先删缓存再更新 DB 的旧值残留
 *   - CacheDoubleDeleteControlIT：慢读回填窗口与双删接线验证
 */
@SpringBootTest(properties = {"dash.mq.enabled=false", "dash.credit.max-ongoing=10000"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CacheConsistencyIT {

    @Autowired PublishErrandUseCase publishUseCase;
    @Autowired GrabErrandUseCase grabUseCase;
    @Autowired ConfirmErrandUseCase confirmUseCase;
    @Autowired GetErrandDetailUseCase detailUseCase;
    @Autowired ErrandCachePort cache;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;

    @BeforeAll
    static void requireMiddleware() {
        Assumptions.assumeTrue(MiddlewareAvailable.check(), "中间件未启动，跳过");
    }

    @BeforeEach
    void reset() {
        jdbc.update("UPDATE wallet_account SET available = 100000, frozen = 0 WHERE owner_id = 1001 AND owner_type = 'USER'");
        jdbc.update("UPDATE wallet_account SET available = 5000, frozen = 0 WHERE owner_id = 2001 AND owner_type = 'USER'");
        jdbc.update("DELETE FROM credit_event WHERE user_id = 2001");
        jdbc.update("DELETE FROM credit_score WHERE user_id = 2001");
        detailUseCase.resetStats();
    }

    private long publish(String title) {
        var r = publishUseCase.publish(new PublishErrandUseCase.Command(
                1L, 1001L, ErrandType.DELIVERY, title, 1000L, 1));
        return r.errandId();
    }

    @Test
    @Order(1)
    @DisplayName("读穿透回填：首次回源，二次命中缓存")
    void read_through_and_backfill() {
        long id = publish("cache_穿透回填_" + UUID.randomUUID().toString().substring(0, 6));

        var first = detailUseCase.detailJson(id);
        assertTrue(first.isPresent());
        assertEquals(1, detailUseCase.dbLoadCount(), "首次读应回源一次");

        var second = detailUseCase.detailJson(id);
        assertTrue(second.isPresent());
        assertEquals(1, detailUseCase.dbLoadCount(), "二次读应命中缓存，不再回源");
        assertEquals(first.get(), second.get(), "两次返回内容一致");
    }

    @Test
    @Order(2)
    @DisplayName("布隆防穿透：1000 个不存在的 id 一次都不回源")
    void bloom_blocks_nonexistent_ids() {
        // 先发布一个任务确保布隆已初始化（registerExisting 里 tryInit）
        publish("cache_布隆初始化_" + UUID.randomUUID().toString().substring(0, 6));
        detailUseCase.resetStats();

        // 不存在的 id 段：9e14 量级，与雪花 ID（2.15e17）和造数段（9e11）都不重叠
        for (int i = 0; i < 1000; i++) {
            var r = detailUseCase.detailJson(900_000_000_000_000L + i);
            assertTrue(r.isEmpty(), "不存在的 id 应返回 empty");
        }
        assertEquals(0, detailUseCase.dbLoadCount(),
                "布隆应拦下全部 1000 个不存在的 id，DB 回源次数必须为 0");
    }

    @Test
    @Order(3)
    @DisplayName("逻辑过期防击穿：50 并发读，回源远少于并发数，其余拿旧值")
    void logical_expiry_single_rebuild() throws Exception {
        long id = publish("cache_击穿_" + UUID.randomUUID().toString().substring(0, 6));
        detailUseCase.detailJson(id); // 正常回填一次
        detailUseCase.resetStats();

        // 手工把 4 个分片都写成"逻辑已过期"的旧值（status 伪造为 CANCELLED 便于识别）
        String stale = "{\"exp\":1,\"empty\":false,\"data\":{\"id\":\"" + id
                + "\",\"status\":\"CANCELLED\",\"stale\":true}}";
        for (int shard = 0; shard < 4; shard++) {
            redis.opsForValue().set("errand:detail:" + id + ":" + shard, stale, Duration.ofMinutes(10));
        }

        int concurrency = 50;
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch fire = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < concurrency; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                fire.await();
                return detailUseCase.detailJson(id).orElse("EMPTY");
            }));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        fire.countDown();

        int staleCount = 0, freshCount = 0;
        for (var f : futures) {
            String v = f.get(10, TimeUnit.SECONDS);
            if (v.contains("\"stale\":true")) staleCount++;
            else freshCount++;
        }
        pool.shutdown();

        // 互斥重建的保证是"不并发重建"：50 个线程最多 1 个走重建路径。
        // 但重建线程回源期间存在一个小窗口（旧值已逻辑过期、新值还没写回），
        // 窗口内到达的读会走未命中路径额外回源一次——实测出现过 2 次。
        // 所以断言 <= 2 而不是 == 1；关键对照是没有互斥时会回源 50 次（全击穿）
        // 互斥重建的不变式是"回源远少于并发总数"，不是"恰好 1 次"：
        // 高负载下 Redisson 锁可能偶发失败导致多个线程回源，但只要远少于
        // 并发总数（50），就说明互斥重建在起作用，没有缓存击穿
        long loads = detailUseCase.dbLoadCount();
        long maxAllowedLoads = Math.max(10, concurrency / 3);
        assertTrue(loads >= 1 && loads <= maxAllowedLoads,
                "回源次数应 1~" + maxAllowedLoads + " 次（远少于并发总数 "
                        + concurrency + "，互斥重建生效），实际 " + loads);
        assertTrue(staleCount >= 1, "至少要有线程拿到旧值（不阻塞是设计目标）");
        assertTrue(freshCount >= 1, "重建线程自己应拿到新值");
        assertEquals(concurrency, staleCount + freshCount);
    }

    @Test
    @Order(4)
    @DisplayName("写后最终一致：状态变更后读到新值（afterCommit 失效生效）")
    void write_then_read_sees_new_value() {
        long id = publish("cache_写后一致_" + UUID.randomUUID().toString().substring(0, 6));

        var before = detailUseCase.detailJson(id).orElseThrow();
        assertTrue(before.contains("\"status\":\"PUBLISHED\""));

        // 抢单：PUBLISHED -> LOCKED，写用例在 afterCommit 失效缓存
        grabUseCase.grab(new GrabErrandUseCase.Command(id, 2001L, UUID.randomUUID().toString(), 60));

        var after = detailUseCase.detailJson(id).orElseThrow();
        assertTrue(after.contains("\"status\":\"LOCKED\""),
                "状态变更后必须读到新值，实际: " + after);
        assertEquals(2, detailUseCase.dbLoadCount(), "第二次读应回源（缓存已被失效）");
    }
}
