package com.campusdash.it;

import com.campusdash.application.usecase.PublishErrandUseCase;
import com.campusdash.application.usecase.query.CacheConsistencyCheckUseCase;
import com.campusdash.application.usecase.query.GetErrandDetailUseCase;
import com.campusdash.domain.errand.model.ErrandType;
import com.campusdash.domain.errand.ports.ErrandCachePort;
import com.campusdash.domain.errand.ports.SyncDiffRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 一致性校验 job 验证：正常情况零差异；手工制造脏缓存后被检出并修正。
 *
 * 校验 job 是缓存链路的哨兵：afterCommit 删除 / 延迟双删 / TTL 是主手段，
 * 它负责检出主手段漏掉的场景。"正常为空 + 脏数据能检出"两条都要测，
 * 只测前者无法证明哨兵真的在干活。
 */
// 样本量设大：测试表里积累了前几期的历史任务，RAND() 抽样必须全覆盖
// 才能保证抽中本用例刚发布的任务（生产配置仍是 100，抽样即可）
@SpringBootTest(properties = {"dash.mq.enabled=false", "dash.cache.check-sample-size=10000"})
class CacheCheckIT {

    @Autowired PublishErrandUseCase publishUseCase;
    @Autowired GetErrandDetailUseCase detailUseCase;
    @Autowired CacheConsistencyCheckUseCase checkUseCase;
    @Autowired ErrandCachePort cache;
    @Autowired SyncDiffRepository syncDiffRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;

    @BeforeAll
    static void requireMiddleware() {
        Assumptions.assumeTrue(MiddlewareAvailable.check(), "中间件未启动，跳过");
    }

    @BeforeEach
    void reset() {
        jdbc.update("UPDATE wallet_account SET available = 100000, frozen = 0 WHERE owner_id = 1001 AND owner_type = 'USER'");
        jdbc.update("DELETE FROM sync_diff");
        // 清掉对照实验故意残留的脏缓存（BEFORE_COMMIT / 慢读窗口用例按设计不纠正旧值），
        // 否则全覆盖校验会检出它们——哨兵没错，是测试间的状态污染
        var keys = redis.keys("errand:detail:*");
        if (!keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    @DisplayName("正常情况：缓存与 DB 一致，校验零差异")
    void no_diff_when_consistent() {
        var pub = publishUseCase.publish(new PublishErrandUseCase.Command(
                1L, 1001L, ErrandType.DELIVERY,
                "校验_正常_" + UUID.randomUUID().toString().substring(0, 6), 1000L, 1));
        detailUseCase.detailJson(pub.errandId()); // 回填缓存

        int diffs = checkUseCase.runOnce();
        assertEquals(0, diffs, "一致时不应检出差异");
        assertEquals(0, syncDiffRepository.countSince(Instant.now().minusSeconds(60)));
    }

    @Test
    @DisplayName("脏缓存：检出差异、落 sync_diff、删缓存修正")
    void dirty_cache_detected_and_fixed() {
        var pub = publishUseCase.publish(new PublishErrandUseCase.Command(
                1L, 1001L, ErrandType.DELIVERY,
                "校验_脏_" + UUID.randomUUID().toString().substring(0, 6), 1000L, 1));
        long id = pub.errandId();
        detailUseCase.detailJson(id); // 回填正确缓存

        // 手工写入脏值：status 伪造为 SETTLED（DB 里是 PUBLISHED）
        String dirty = "{\"id\":\"" + id + "\",\"status\":\"SETTLED\",\"version\":99,\"rewardCents\":1000}";
        cache.put(id, dirty);

        int diffs = checkUseCase.runOnce();
        assertTrue(diffs >= 1, "脏缓存必须被检出");
        assertTrue(syncDiffRepository.countSince(Instant.now().minusSeconds(60)) >= 1,
                "差异必须落 sync_diff 表");

        // 修正动作是删缓存：再读应回源拿到 DB 的真实值
        String after = detailUseCase.detailJson(id).orElseThrow();
        assertTrue(after.contains("\"status\":\"PUBLISHED\""),
                "修正后应读到 DB 真实状态，实际: " + after);
    }
}
