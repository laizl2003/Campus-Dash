package com.campusdash.it;

import com.campusdash.application.usecase.GrabErrandUseCase;
import com.campusdash.application.usecase.PublishErrandUseCase;
import com.campusdash.application.usecase.query.GetErrandDetailUseCase;
import com.campusdash.domain.errand.model.ErrandType;
import com.campusdash.domain.errand.ports.CacheEvictDelayPort;
import com.campusdash.domain.errand.ports.ErrandCachePort;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 对照实验二：慢读回填窗口的确定性模拟 + 延迟双删接线验证。
 *
 * ── 窗口是什么 ──
 * AFTER_COMMIT 删除之后仍有极小窗口：一个在事务提交前就开始读的慢请求，
 * 可能在删除之后才把旧值写回缓存。这个窗口是概率性的，
 * 本实验按时序手工模拟（与对照实验一同一个思路：测试要确定性复现）。
 *
 * ── 本实验证明两件事 ──
 *   1. 窗口真实存在：慢读写回旧值后，若没有第二次删除，旧值会一直残留
 *   2. 双删接线正确：写用例确实登记了延迟 500ms 的第二次删除
 *      （集成测试关 MQ，投递走 Noop 适配器不真发；真实投递在联调阶段验证）
 */
@SpringBootTest(properties = {"dash.mq.enabled=false"})
class CacheDoubleDeleteControlIT {

    /** 记录型投递端口：替换 Noop 适配器，用来断言双删的接线 */
    @TestConfiguration
    static class RecordingConfig {
        @Bean
        @Primary
        CacheEvictDelayPort recordingCacheEvictDelayPort() {
            return new RecordingCacheEvictDelayPort();
        }
    }

    static class RecordingCacheEvictDelayPort implements CacheEvictDelayPort {
        final List<Record> records = new CopyOnWriteArrayList<>();
        record Record(long errandId, Instant deliverAt, Instant scheduledAt) {}

        @Override
        public void scheduleEvict(long errandId, Instant deliverAt) {
            records.add(new Record(errandId, deliverAt, Instant.now()));
        }
    }

    @Autowired PublishErrandUseCase publishUseCase;
    @Autowired GrabErrandUseCase grabUseCase;
    @Autowired GetErrandDetailUseCase detailUseCase;
    @Autowired ErrandCachePort cache;
    @Autowired CacheEvictDelayPort delayPort;
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
        ((RecordingCacheEvictDelayPort) unwrap(delayPort)).records.clear();
    }

    private CacheEvictDelayPort unwrap(CacheEvictDelayPort port) {
        // @Primary 注入的可能被 Spring 包一层，直接类型判断
        return port;
    }

    @Test
    @DisplayName("双删接线：写用例提交后登记了延迟约 500ms 的第二次删除")
    void double_delete_is_scheduled_after_commit() {
        var pub = publishUseCase.publish(new PublishErrandUseCase.Command(
                1L, 1001L, ErrandType.DELIVERY,
                "对照_双删接线_" + UUID.randomUUID().toString().substring(0, 6), 1000L, 1));
        long id = pub.errandId();

        grabUseCase.grab(new GrabErrandUseCase.Command(id, 2001L, UUID.randomUUID().toString(), 60));

        var records = ((RecordingCacheEvictDelayPort) delayPort).records;
        assertEquals(1, records.size(), "抢单成功后应恰好登记一次延迟双删");
        var r = records.get(0);
        assertEquals(id, r.errandId());
        long delayMs = Duration.between(r.scheduledAt(), r.deliverAt()).toMillis();
        assertTrue(delayMs >= 400 && delayMs <= 700,
                "第二次删除应延迟约 500ms，实际 " + delayMs + "ms");
    }

    @Test
    @DisplayName("慢读窗口模拟：没有第二次删除时，旧值残留到 TTL")
    void slow_read_window_leaves_stale_without_second_delete() {
        var pub = publishUseCase.publish(new PublishErrandUseCase.Command(
                1L, 1001L, ErrandType.DELIVERY,
                "对照_慢读窗口_" + UUID.randomUUID().toString().substring(0, 6), 1000L, 1));
        long id = pub.errandId();

        // 1. 慢读开始：回源读到 PUBLISHED（此时事务尚未提交）
        String staleJson = detailUseCase.detailJson(id).orElseThrow();
        assertTrue(staleJson.contains("\"status\":\"PUBLISHED\""));

        // 2. 写提交：DB 变 LOCKED，afterCommit 第一次删除生效
        grabUseCase.grab(new GrabErrandUseCase.Command(id, 2001L, UUID.randomUUID().toString(), 60));

        // 3. 慢读完成：把第 1 步读到的旧值写回缓存（发生在第一次删除之后）
        cache.put(id, staleJson);

        // 4. 集成测试关 MQ，第二次删除不会真的执行（Noop 适配器）——
        //    于是旧值残留。这正是双删要覆盖的窗口，也证明窗口真实存在
        String after = detailUseCase.detailJson(id).orElseThrow();
        assertTrue(after.contains("\"status\":\"PUBLISHED\""),
                "慢读写回后旧值应残留（第二次删除未执行），实际: " + after);

        String dbStatus = jdbc.queryForObject("SELECT status FROM errand WHERE id = ?", String.class, id);
        assertEquals("LOCKED", dbStatus, "DB 是新值——不一致只存在于缓存层，等第二次删除或 TTL 纠正");
    }
}
