package com.campusdash.application.usecase.query;

import com.campusdash.domain.errand.model.Errand;
import com.campusdash.domain.errand.ports.ErrandCachePort;
import com.campusdash.domain.errand.ports.ErrandRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 任务详情查询：Cache Aside 读路径的三段逻辑。
 *
 * ── 读路径 ──
 *   1. 读缓存命中且未逻辑过期 → 直接返回
 *   2. 命中但逻辑过期 → 抢重建权：抢到的回源重建，抢不到的返回旧值（防击穿）
 *   3. 未命中 → 布隆判存在性（防穿透）→ 回源 DB → 回填（查不到则写空值缓存）
 *
 * ── 为什么逻辑过期时"返回旧值"而不是"阻塞等重建" ──
 * 热点任务缓存到期瞬间会有几百个并发读。阻塞等待会把这几百个线程挂在锁上，
 * 等于把缓存击穿转化成了线程池耗尽。返回旧值的代价是最多几百毫秒的数据陈旧，
 * 而任务详情这个场景完全可以接受（名额判定与抢单裁决绝不读缓存，走 Redis Lua + DB）。
 *
 * dbLoadCount 用于测试断言"回源恰好几次"，生产环境也可作为指标暴露。
 */
@Service
public class GetErrandDetailUseCase {

    private static final Logger log = LoggerFactory.getLogger(GetErrandDetailUseCase.class);

    private final ErrandRepository errandRepository;
    private final ErrandCachePort cache;

    /** 回源计数（测试用来验证防穿透与防击穿是否真的生效） */
    private final AtomicLong dbLoadCount = new AtomicLong();
    private final AtomicLong cacheHitCount = new AtomicLong();
    private final AtomicLong requestCount = new AtomicLong();

    public GetErrandDetailUseCase(ErrandRepository errandRepository, ErrandCachePort cache) {
        this.errandRepository = errandRepository;
        this.cache = cache;
    }

    /** 返回详情 JSON（presentation 层直接透出）；empty 表示任务不存在 */
    public Optional<String> detailJson(long errandId) {
        requestCount.incrementAndGet();

        Optional<ErrandCachePort.CachedErrand> cached = cache.get(errandId);
        if (cached.isPresent()) {
            ErrandCachePort.CachedErrand c = cached.get();
            if (c.isEmpty()) {
                // 空值缓存命中：说明不久前查过且确实不存在，直接返回
                cacheHitCount.incrementAndGet();
                return Optional.empty();
            }
            if (!c.logicallyExpired()) {
                cacheHitCount.incrementAndGet();
                return Optional.of(c.payloadJson());
            }
            // 逻辑过期：抢到重建权的去重建，抢不到的先返回旧值
            if (cache.tryAcquireRebuild(errandId)) {
                try {
                    return reload(errandId);
                } finally {
                    cache.releaseRebuild(errandId);
                }
            }
            cacheHitCount.incrementAndGet();
            log.debug("逻辑过期但未抢到重建权，返回旧值 errandId={}", errandId);
            return Optional.of(c.payloadJson());
        }

        // 完全未命中：先用布隆挡掉不存在的 id（防穿透）
        if (!cache.mightExist(errandId)) {
            log.debug("布隆判定不存在，不回源 errandId={}", errandId);
            return Optional.empty();
        }
        return reload(errandId);
    }

    private Optional<String> reload(long errandId) {
        dbLoadCount.incrementAndGet();
        Optional<Errand> found = errandRepository.findById(errandId);
        if (found.isEmpty()) {
            // 布隆误判（1% 概率）或任务真的不存在：写空值缓存，避免同一个 id 反复打 DB
            cache.putEmpty(errandId);
            return Optional.empty();
        }
        String json = toJson(found.get());
        cache.put(errandId, json);
        return Optional.of(json);
    }

    /**
     * 详情 JSON。手写而不用 Jackson：字段固定且要与 ErrandController.toCard 对齐，
     * 手写能保证缓存里存的就是接口要返回的形状，不会因序列化配置变化而漂移。
     * id 一律用字符串（雪花 ID 超过 JS 安全整数，见 P4 踩坑第 11 条）。
     */
    private String toJson(Errand e) {
        return String.format(
                "{\"id\":\"%d\",\"title\":\"%s\",\"status\":\"%s\",\"type\":\"%s\","
                        + "\"rewardCents\":%d,\"slotTotal\":%d,\"slotTaken\":%d,"
                        + "\"publisherId\":\"%d\",\"grabberId\":\"%d\",\"round\":%d,\"version\":%d}",
                e.id(), escape(e.title()), e.status().name(), e.type().name(),
                e.reward().cents(), e.slotTotal(), e.slotTaken(),
                e.publisherId(), e.grabberId() == null ? -1L : e.grabberId(),
                e.round(), e.version());
    }

    private String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public long dbLoadCount() { return dbLoadCount.get(); }
    public long cacheHitCount() { return cacheHitCount.get(); }
    public long requestCount() { return requestCount.get(); }

    /** 命中率：S3 压测报告要用 */
    public double hitRate() {
        long total = requestCount.get();
        return total == 0 ? 0 : (double) cacheHitCount.get() / total;
    }

    public void resetStats() {
        dbLoadCount.set(0);
        cacheHitCount.set(0);
        requestCount.set(0);
    }
}
