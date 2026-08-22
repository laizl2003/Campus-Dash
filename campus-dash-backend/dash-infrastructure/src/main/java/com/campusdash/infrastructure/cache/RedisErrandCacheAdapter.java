package com.campusdash.infrastructure.cache;

import com.campusdash.domain.errand.ports.ErrandCachePort;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 任务详情缓存的 Redis 实现：Cache Aside + 逻辑过期 + key 分片 + 布隆 + 互斥重建。
 *
 * ── 缓存值格式 ──
 * {"exp":1755500000000,"empty":false,"data":{...详情...}}
 * exp 是逻辑过期时间。物理 TTL 比逻辑过期长一倍，所以逻辑过期后值还在，
 * 读到旧值的线程可以先返回旧值、由一个线程去异步重建（防击穿的关键）。
 *
 * ── 为什么分片 ──
 * 爆款任务的详情是热 Key，所有请求打同一个 Redis slot。写成
 * errand:detail:{id}:{shard} 后随机读一片，把压力散到 N 个 slot。
 * 代价是写放大 N 倍（失效要删 N 片），但详情的读写比极高，这个代价划算。
 *
 * ── 降级 ──
 * 任何 Redis 异常都不向上抛，返回 empty 让调用方回源 DB。
 * 缓存是加速手段，不能成为可用性的单点。
 */
@Component
@ConditionalOnProperty(name = "dash.cache.enabled", havingValue = "true", matchIfMissing = true)
public class RedisErrandCacheAdapter implements ErrandCachePort {

    private static final Logger log = LoggerFactory.getLogger(RedisErrandCacheAdapter.class);

    private static final String KEY_PREFIX = "errand:detail:";
    private static final String BLOOM_NAME = "errand:bloom";
    private static final String REBUILD_LOCK_PREFIX = "errand:rebuild:";

    private final StringRedisTemplate redis;
    private final RedissonClient redisson;
    private final int shards;
    private final long ttlSeconds;
    private final long jitterSeconds;
    private final long emptyTtlSeconds;
    private final boolean bloomEnabled;

    public RedisErrandCacheAdapter(StringRedisTemplate redis,
                                   RedissonClient redisson,
                                   @Value("${dash.cache.shards:4}") int shards,
                                   @Value("${dash.cache.ttl-seconds:600}") long ttlSeconds,
                                   @Value("${dash.cache.jitter-seconds:120}") long jitterSeconds,
                                   @Value("${dash.cache.empty-ttl-seconds:60}") long emptyTtlSeconds,
                                   @Value("${dash.cache.bloom-enabled:true}") boolean bloomEnabled) {
        this.redis = redis;
        this.redisson = redisson;
        this.shards = Math.max(1, shards);
        this.ttlSeconds = ttlSeconds;
        this.jitterSeconds = jitterSeconds;
        this.emptyTtlSeconds = emptyTtlSeconds;
        this.bloomEnabled = bloomEnabled;
    }

    private String key(long errandId, int shard) {
        return KEY_PREFIX + errandId + ":" + shard;
    }

    @Override
    public Optional<CachedErrand> get(long errandId) {
        try {
            // 随机挑一片读：热 Key 的压力被打散到不同 slot
            int shard = ThreadLocalRandom.current().nextInt(shards);
            String raw = redis.opsForValue().get(key(errandId, shard));
            if (raw == null) {
                return Optional.empty();
            }
            return Optional.of(parse(raw));
        } catch (RuntimeException e) {
            // 缓存故障必须降级，不能让它拖垮请求
            log.warn("读缓存失败，降级回源 errandId={}", errandId, e);
            return Optional.empty();
        }
    }

    @Override
    public void put(long errandId, String payloadJson) {
        long physicalTtl = ttlSeconds + ThreadLocalRandom.current().nextLong(-jitterSeconds, jitterSeconds + 1);
        long logicalExpireAt = System.currentTimeMillis() + physicalTtl * 1000 / 2;
        String value = "{\"exp\":" + logicalExpireAt + ",\"empty\":false,\"data\":" + payloadJson + "}";
        writeAllShards(errandId, value, Duration.ofSeconds(Math.max(60, physicalTtl)));
    }

    @Override
    public void putEmpty(long errandId) {
        // 空值缓存的逻辑过期直接设为物理过期：空值没有"返回旧值"的意义
        long logicalExpireAt = System.currentTimeMillis() + emptyTtlSeconds * 1000;
        String value = "{\"exp\":" + logicalExpireAt + ",\"empty\":true,\"data\":null}";
        writeAllShards(errandId, value, Duration.ofSeconds(emptyTtlSeconds));
    }

    private void writeAllShards(long errandId, String value, Duration ttl) {
        try {
            for (int i = 0; i < shards; i++) {
                redis.opsForValue().set(key(errandId, i), value, ttl);
            }
        } catch (RuntimeException e) {
            log.warn("写缓存失败（不影响业务结果）errandId={}", errandId, e);
        }
    }

    @Override
    public void evict(long errandId) {
        try {
            List<String> keys = new java.util.ArrayList<>(shards);
            for (int i = 0; i < shards; i++) {
                keys.add(key(errandId, i));
            }
            redis.delete(keys);
        } catch (RuntimeException e) {
            // 删除失败靠延迟双删与 TTL 兜底，但必须留日志——静默失败会让排查无从下手
            log.error("删除缓存失败 errandId={}，依赖延迟双删与 TTL 兜底", errandId, e);
        }
    }

    /**
     * 重建权：tryLock 不等待（waitTime=0）。
     * 拿不到就立刻返回 false 让调用方返回旧值——阻塞等待会把线程耗在这里，
     * 热 Key 场景下几百个线程一起等，等于把击穿变成了线程池打满。
     */
    @Override
    public boolean tryAcquireRebuild(long errandId) {
        try {
            RLock lock = redisson.getLock(REBUILD_LOCK_PREFIX + errandId);
            return lock.tryLock(0, 10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (RuntimeException e) {
            log.warn("获取重建锁失败 errandId={}", errandId, e);
            return false;
        }
    }

    @Override
    public void releaseRebuild(long errandId) {
        try {
            RLock lock = redisson.getLock(REBUILD_LOCK_PREFIX + errandId);
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (RuntimeException e) {
            log.warn("释放重建锁失败 errandId={}", errandId, e);
        }
    }

    /**
     * 布隆判存在。
     *
     * 注意方向：布隆说"不存在"是确定的（可以直接返 404），
     * 说"存在"可能是误判（1% 概率），回源查不到再写空值缓存即可。
     * 这个方向正好是安全的——不会把真实存在的任务判成不存在。
     */
    @Override
    public boolean mightExist(long errandId) {
        if (!bloomEnabled) {
            return true;
        }
        try {
            RBloomFilter<Long> bloom = redisson.getBloomFilter(BLOOM_NAME);
            if (!bloom.isExists()) {
                // 布隆还没建（比如刚清空 Redis），保守放行，交给 BloomRebuildJob 重建
                return true;
            }
            return bloom.contains(errandId);
        } catch (RuntimeException e) {
            log.warn("布隆查询失败，保守放行 errandId={}", errandId, e);
            return true;
        }
    }

    @Override
    public void registerExisting(long errandId) {
        if (!bloomEnabled) {
            return;
        }
        try {
            RBloomFilter<Long> bloom = redisson.getBloomFilter(BLOOM_NAME);
            if (!bloom.isExists()) {
                bloom.tryInit(100_000L, 0.01);
            }
            bloom.add(errandId);
        } catch (RuntimeException e) {
            log.warn("布隆登记失败 errandId={}", errandId, e);
        }
    }

    private CachedErrand parse(String raw) {
        long exp = extractLong(raw, "exp");
        boolean empty = raw.contains("\"empty\":true");
        String data = null;
        if (!empty) {
            int i = raw.indexOf("\"data\":");
            if (i >= 0) {
                data = raw.substring(i + 7, raw.lastIndexOf('}'));
            }
        }
        return new CachedErrand(data, exp, empty);
    }

    private long extractLong(String json, String field) {
        String needle = "\"" + field + "\":";
        int i = json.indexOf(needle) + needle.length();
        int end = i;
        while (end < json.length() && Character.isDigit(json.charAt(end))) {
            end++;
        }
        return Long.parseLong(json.substring(i, end));
    }
}
