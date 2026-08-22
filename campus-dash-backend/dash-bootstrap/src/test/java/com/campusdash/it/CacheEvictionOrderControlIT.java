package com.campusdash.it;

import com.campusdash.application.usecase.GrabErrandUseCase;
import com.campusdash.application.usecase.PublishErrandUseCase;
import com.campusdash.application.usecase.query.GetErrandDetailUseCase;
import com.campusdash.domain.errand.model.ErrandType;
import com.campusdash.domain.errand.ports.ErrandCachePort;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 对照实验一：先删缓存再更新 DB（BEFORE_COMMIT）会残留旧值。
 *
 * 与 P1 超卖对照实验同构：正确实现通过不能证明什么，
 * 必须证明错误实现真的会错——这里的"错"就是读到长期不一致的旧状态。
 *
 * 实验用确定性模拟而不是压竞态窗口：
 *   BEFORE_COMMIT 的语义是"缓存在 DB 新值可见之前就被删了"，
 *   于是删后、提交前的读会把旧值写回缓存。我们按这个时序手工执行：
 *     1. 先删缓存（模拟事务提交前的删除）
 *     2. 读一次（此时 DB 还是旧值，旧值被写回缓存）
 *     3. 再更新 DB（抢单）
 *     4. 读：拿到的仍是旧值——不一致产生且会持续到 TTL
 *   竞态窗口是概率性的，测试必须确定性复现，所以用时序模拟而不是真压窗口。
 *
 * 对照组在 CacheConsistencyIT.write_then_read_sees_new_value：
 * AFTER_COMMIT 下同样的操作序列读到的是新值。
 */
@SpringBootTest(properties = {
        "dash.mq.enabled=false",
        "dash.cache.eviction-order=BEFORE_COMMIT"
})
class CacheEvictionOrderControlIT {

    @Autowired PublishErrandUseCase publishUseCase;
    @Autowired GrabErrandUseCase grabUseCase;
    @Autowired GetErrandDetailUseCase detailUseCase;
    @Autowired ErrandCachePort cache;
    @Autowired JdbcTemplate jdbc;

    @BeforeAll
    static void requireMiddleware() {
        Assumptions.assumeTrue(MiddlewareAvailable.check(), "中间件未启动，跳过");
    }

    @BeforeEach
    void reset() {
        jdbc.update("UPDATE wallet_account SET available = 100000, frozen = 0 WHERE owner_id = 1001 AND owner_type = 'USER'");
        jdbc.update("UPDATE wallet_account SET available = 5000, frozen = 0 WHERE owner_id = 2001 AND owner_type = 'USER'");
        detailUseCase.resetStats();
    }

    @Test
    @DisplayName("对照实验：先删缓存再更新 DB，慢读把旧值写回且无人再删")
    void before_commit_eviction_leaves_stale_value() {
        var pub = publishUseCase.publish(new PublishErrandUseCase.Command(
                1L, 1001L, ErrandType.DELIVERY,
                "对照_先删后更_" + UUID.randomUUID().toString().substring(0, 6), 1000L, 1));
        long id = pub.errandId();

        // 1. 读一次，缓存回填 PUBLISHED，并记下旧值
        String staleJson = detailUseCase.detailJson(id).orElseThrow();
        assertTrue(staleJson.contains("\"status\":\"PUBLISHED\""));

        // 2. 模拟 BEFORE_COMMIT 的删除：发生在 DB 更新可见之前
        cache.evict(id);

        // 3. 更新 DB：PUBLISHED -> LOCKED。
        //    AFTER_COMMIT 实现里，写用例提交后的那次删除发生在这里；
        //    但 BEFORE_COMMIT 的删除已经在第 2 步用掉了，此后不会再有删除
        grabUseCase.grab(new GrabErrandUseCase.Command(id, 2001L, UUID.randomUUID().toString(), 60));

        // 4. 慢读登场：它在第 2 步删除之后才开始回源。
        //    真实场景里这个读可能开始于事务提交前（读到旧值）、完成于提交后（把旧值写回）；
        //    这里直接按"写回旧值"模拟它的最终效果
        cache.put(id, staleJson);

        // 5. 再读：缓存里是旧值，且没有任何机制会再纠正它（双删也救不了——
        //    双删的第二次删除同样挂在 BEFORE_COMMIT 时点，早已执行完）
        String after = detailUseCase.detailJson(id).orElseThrow();
        assertTrue(after.contains("\"status\":\"PUBLISHED\""),
                "对照实验预期读到旧值（证明错误顺序真的会不一致），实际: " + after);
        assertFalse(after.contains("\"status\":\"LOCKED\""),
                "若读到新值，说明模拟时序不成立，实验无效");

        // 6. 佐证 DB 里确实是新值：不一致只存在于缓存层
        String dbStatus = jdbc.queryForObject(
                "SELECT status FROM errand WHERE id = ?", String.class, id);
        assertEquals("LOCKED", dbStatus, "DB 是新值、缓存是旧值——这就是缓存与 DB 不一致");
    }
}
