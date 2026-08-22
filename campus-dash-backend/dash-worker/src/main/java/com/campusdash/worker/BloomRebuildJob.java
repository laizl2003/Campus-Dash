package com.campusdash.worker;

import com.campusdash.domain.errand.ports.ErrandCachePort;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 布隆过滤器重建：worker 启动时把存量任务 id 灌入布隆。
 *
 * ── 为什么需要 ──
 * 布隆只加不减，且只存在于 Redis。两种情况会导致布隆缺失存量 id：
 *   1. Redis 被清空 / 布隆 key 过期或被误删
 *   2. 新环境首次部署
 * 缺失后，存量任务的详情查询会被防穿透逻辑判为"不存在"（P5 实测踩过的坑：
 * 测试用 SQL 造的任务没走发布用例，被布隆误杀）。
 *
 * ── 并发保护 ──
 * 多个 worker 实例同时启动时，用 Redisson 锁保证只有一个实例执行重建。
 *
 * ── 已知边界 ──
 * 重建是全表扫描，任务量到百万级要改分批 + 断点续传。校园量级（万级）无压力。
 */
@Component
public class BloomRebuildJob {

    private static final Logger log = LoggerFactory.getLogger(BloomRebuildJob.class);
    private static final String REBUILD_LOCK = "bloom:rebuild:lock";

    private final JdbcTemplate jdbc;
    private final ErrandCachePort cache;
    private final RedissonClient redisson;
    private final boolean bloomEnabled;

    public BloomRebuildJob(JdbcTemplate jdbc,
                           ErrandCachePort cache,
                           RedissonClient redisson,
                           @Value("${dash.cache.bloom-enabled:true}") boolean bloomEnabled) {
        this.jdbc = jdbc;
        this.cache = cache;
        this.redisson = redisson;
        this.bloomEnabled = bloomEnabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void rebuildOnStartup() {
        if (!bloomEnabled) {
            return;
        }
        RLock lock = redisson.getLock(REBUILD_LOCK);
        boolean acquired = false;
        try {
            // 等 5s 抢锁，持锁最长 5 分钟（防实例崩溃后锁不释放）
            acquired = lock.tryLock(5, 300, TimeUnit.SECONDS);
            if (!acquired) {
                log.info("其他实例正在重建布隆，跳过");
                return;
            }
            List<Long> ids = jdbc.queryForList("SELECT id FROM errand", Long.class);
            for (Long id : ids) {
                cache.registerExisting(id);
            }
            log.info("布隆重建完成，灌入 {} 个存量任务 id", ids.size());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            // 重建失败不阻塞 worker 启动：mightExist 在布隆不存在时保守放行，
            // 代价是防穿透暂时失效，等下次重启再重建
            log.error("布隆重建失败（防穿透暂时降级为放行）", e);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
