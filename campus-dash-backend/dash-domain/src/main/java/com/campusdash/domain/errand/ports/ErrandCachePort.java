package com.campusdash.domain.errand.ports;

import java.util.Optional;

/**
 * 任务详情缓存端口。
 *
 * 端口刻意不暴露 Redis 概念（分片、TTL、Lua），领域层只知道
 * "读缓存、写缓存、失效缓存"三件事。分片与逻辑过期都是适配器的内部实现。
 *
 * ── 为什么只缓存详情，不缓存列表 ──
 * 列表是分页查询、随任意任务状态变化而变，失效面太大（改一个任务要失效所有
 * 包含它的页），命中率也低。详情是点查、key 明确、失效精确，投入产出比最高。
 * 列表继续走 MySQL 的 idx_campus_status，校园量级完全够。
 */
public interface ErrandCachePort {

    /**
     * 读缓存。
     *
     * @return empty 表示未命中（需要回源）；present 时可能是逻辑过期的旧值，
     *         由 {@link CachedErrand#logicallyExpired()} 判断
     */
    Optional<CachedErrand> get(long errandId);

    /** 写缓存：回填全部分片，物理 TTL 带随机抖动 */
    void put(long errandId, String payloadJson);

    /** 缓存空值：防穿透用，TTL 短（默认 60s） */
    void putEmpty(long errandId);

    /** 失效：删除该任务的全部分片 */
    void evict(long errandId);

    /**
     * 尝试获取重建权（防击穿）。
     *
     * @return true 表示拿到重建权，调用方应该回源并回填；
     *         false 表示别人正在重建，调用方直接返回旧值
     */
    boolean tryAcquireRebuild(long errandId);

    void releaseRebuild(long errandId);

    /** 布隆过滤器判存在性。返回 false 时可以确定不存在（防穿透） */
    boolean mightExist(long errandId);

    /** 任务发布后登记到布隆 */
    void registerExisting(long errandId);

    /** 缓存值：payload 是详情 JSON，isEmpty 标记空值缓存 */
    record CachedErrand(String payloadJson, long logicalExpireAt, boolean isEmpty) {
        public boolean logicallyExpired() {
            return System.currentTimeMillis() > logicalExpireAt;
        }
    }
}
