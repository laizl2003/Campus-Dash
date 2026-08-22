package com.campusdash.infrastructure.cache;

import com.campusdash.domain.errand.ports.ErrandCachePort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 缓存关闭时的实现：永不命中、写入即丢弃。
 *
 * 存在意义有两个：
 *   1. dash.cache.enabled=false 时整套功能退回 P4 行为，用于 S3-a 的"关缓存基准"对照
 *   2. 证明缓存是可摘除的——业务正确性不依赖它（验收标准第 5 条）
 */
@Component
@ConditionalOnProperty(name = "dash.cache.enabled", havingValue = "false")
public class NoopErrandCacheAdapter implements ErrandCachePort {

    @Override
    public Optional<CachedErrand> get(long errandId) {
        return Optional.empty();
    }

    @Override
    public void put(long errandId, String payloadJson) {
    }

    @Override
    public void putEmpty(long errandId) {
    }

    @Override
    public void evict(long errandId) {
    }

    @Override
    public boolean tryAcquireRebuild(long errandId) {
        // 没有缓存就没有击穿问题，每个请求都直接回源
        return true;
    }

    @Override
    public void releaseRebuild(long errandId) {
    }

    @Override
    public boolean mightExist(long errandId) {
        return true;
    }

    @Override
    public void registerExisting(long errandId) {
    }
}
