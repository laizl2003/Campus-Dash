package com.campusdash.application.usecase;

import com.campusdash.domain.errand.ports.CacheEvictDelayPort;
import com.campusdash.domain.errand.ports.ErrandCachePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 缓存失效的统一入口：事务提交后删缓存 + 登记延迟双删。
 *
 * ── 为什么必须在 afterCommit 删，而不是事务内 ──
 * 事务内删缓存有两个问题：
 *   1. 事务后续回滚了，缓存已被删——DB 没变但缓存空了，白回源一次（不致命）
 *   2. 更糟：删完到提交之间的读请求会从 DB 读到旧值并写回缓存，
 *      于是"缓存里是旧值、DB 里是新值"长期不一致
 * 放在 afterCommit 就没有第 2 个窗口——删除发生在新值已可见之后。
 *
 * ── 为什么还要延迟双删 ──
 * afterCommit 删除之后仍有极小窗口：一个在提交前就开始读的慢请求，
 * 可能在删除之后才把旧值写回缓存。500ms 后再删一次覆盖这个窗口。
 *
 * ── 为什么双删不走本地消息表 ──
 * 双删是加固手段而非正确性保障：丢一条最多让某个 key 多存 10 分钟旧值（TTL 兜底），
 * 且一致性校验 job 也会检出。为它在每个写用例上加一次 DB 写不划算。
 * 这与 P2/P3 的判定标准一致：丢了会导致业务错误的才用消息表。
 */
@Component
public class CacheEvictSupport {

    private static final Logger log = LoggerFactory.getLogger(CacheEvictSupport.class);

    private final ErrandCachePort cache;
    private final CacheEvictDelayPort delayPort;
    private final long doubleDeleteMillis;
    private final String evictionOrder;
    private final boolean doubleDeleteEnabled;

    private final AtomicLong evictFailureCount = new AtomicLong();

    public CacheEvictSupport(ErrandCachePort cache,
                             CacheEvictDelayPort delayPort,
                             @Value("${dash.cache.double-delete-ms:500}") long doubleDeleteMillis,
                             @Value("${dash.cache.double-delete-enabled:true}") boolean doubleDeleteEnabled,
                             @Value("${dash.cache.eviction-order:AFTER_COMMIT}") String evictionOrder) {
        this.cache = cache;
        this.delayPort = delayPort;
        this.doubleDeleteMillis = doubleDeleteMillis;
        this.doubleDeleteEnabled = doubleDeleteEnabled;
        this.evictionOrder = evictionOrder;
    }

    /**
     * 在当前事务提交后失效缓存。
     *
     * eviction-order=BEFORE_COMMIT 是**故意留的错误实现开关**，
     * 供 CacheConsistencyIT 的对照实验压出"缓存长期为旧值"。
     * 生产配置永远是 AFTER_COMMIT。
     */
    public void evictAfterCommit(long errandId) {
        if ("BEFORE_COMMIT".equalsIgnoreCase(evictionOrder)) {
            // 错误实现：事务还没提交就删，读请求会把旧值写回来
            doEvict(errandId);
            scheduleDoubleDelete(errandId);
            return;
        }

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // 没有事务上下文（比如从消费者直接调用）：立即删
            doEvict(errandId);
            scheduleDoubleDelete(errandId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                doEvict(errandId);
                scheduleDoubleDelete(errandId);
            }
        });
    }

    private void doEvict(long errandId) {
        try {
            cache.evict(errandId);
        } catch (RuntimeException e) {
            // 失败不抛：资金/状态已经提交，不能因为删缓存失败而让接口报错。
            // 但必须计数 + 告警，否则就是静默不一致
            evictFailureCount.incrementAndGet();
            log.error("缓存失效失败 errandId={}，累计失败 {} 次", errandId, evictFailureCount.get(), e);
        }
    }

    private void scheduleDoubleDelete(long errandId) {
        if (!doubleDeleteEnabled) {
            return;
        }
        try {
            delayPort.scheduleEvict(errandId, Instant.now().plusMillis(doubleDeleteMillis));
        } catch (RuntimeException e) {
            log.warn("延迟双删登记失败 errandId={}，靠 TTL 与校验 job 兜底", errandId, e);
        }
    }

    public long evictFailureCount() {
        return evictFailureCount.get();
    }
}
