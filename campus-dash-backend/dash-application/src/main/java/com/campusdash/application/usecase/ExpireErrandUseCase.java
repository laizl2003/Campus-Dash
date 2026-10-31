package com.campusdash.application.usecase;

import com.campusdash.application.usecase.query.GetErrandDetailUseCase;
import com.campusdash.domain.errand.model.Errand;
import com.campusdash.domain.errand.model.ErrandStatus;
import com.campusdash.domain.errand.ports.ErrandCachePort;
import com.campusdash.domain.errand.ports.ErrandRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * 任务过期：到点仍无人接单，PUBLISHED -> EXPIRED。
 *
 * 两条防线都要有：
 * 1. 领域状态机 transitTo：非法流转直接抛异常，保证状态图正确；
 * 2. SQL 带 where status='PUBLISHED'：并发下只有一次能改成功，
 *    抢单（PUBLISHED -> LOCKED）与过期同时发生时，后到的那个受影响行数为 0。
 *
 * 为什么不能只靠延时消息"到点就改"：消息可能重复投递、可能延迟，
 * 也可能用户在最后一秒抢走了单子。所以消费时必须重新读库判断。
 */
@Service
public class ExpireErrandUseCase {

    private static final Logger log = LoggerFactory.getLogger(ExpireErrandUseCase.class);

    private final ErrandRepository errandRepository;
    @SuppressWarnings("unused")
    private final GetErrandDetailUseCase detailUseCase;
    private final ErrandCachePort cachePort;

    public ExpireErrandUseCase(ErrandRepository errandRepository,
                               GetErrandDetailUseCase detailUseCase,
                               ErrandCachePort cachePort) {
        this.errandRepository = errandRepository;
        this.detailUseCase = detailUseCase;
        this.cachePort = cachePort;
    }

    /**
     * 处理一条过期消息。
     *
     * @return EXPIRED 成功过期 / SKIPPED 无需处理（已过期、已被抢、无过期时间、未到点）
     */
    @Transactional
    public Outcome expire(long errandId) {
        Optional<Errand> found = errandRepository.findById(errandId);
        if (found.isEmpty()) {
            return Outcome.SKIPPED;
        }
        Errand errand = found.get();

        // 已被抢走或已流转：什么都不做
        if (errand.status() != ErrandStatus.PUBLISHED) {
            log.debug("过期消息跳过：状态已是 {} errandId={}", errand.status(), errandId);
            return Outcome.SKIPPED;
        }
        // 永久有效的任务不处理
        if (errand.expiredAt() == null) {
            return Outcome.SKIPPED;
        }
        // 消息提前到达（例如用户改过过期时间）：跳过，等真正的到期消息
        if (errand.expiredAt().isAfter(Instant.now())) {
            log.debug("过期消息早到，跳过 errandId={} expiredAt={}", errandId, errand.expiredAt());
            return Outcome.SKIPPED;
        }

        // 领域状态机先校验：EXPIRED 必须是 PUBLISHED 的合法后继。
        // 注意要传"读到的版本号"，transitTo 内部会校验并自增，
        // 之后再拿 errand.version() 去 CAS 会少 1，导致永远更新不到（实测踩过）。
        long expectedVersion = errand.version();
        errand.transitTo(ErrandStatus.EXPIRED, expectedVersion);

        // SQL 再兜一次并发：只有仍是 PUBLISHED 且版本没变才能改动
        int updated = errandRepository.casExpire(errandId, expectedVersion);
        if (updated == 0) {
            log.info("过期未生效（并发抢单或重复消费）errandId={}", errandId);
            return Outcome.SKIPPED;
        }

        errandRepository.appendStatusLog(errandId, ErrandStatus.PUBLISHED, ErrandStatus.EXPIRED,
                errand.round(), Errand.SYSTEM_OPERATOR);
        cachePort.evict(errandId);
        log.info("任务已过期 errandId={} expiredAt={}", errandId, errand.expiredAt());
        return Outcome.EXPIRED;
    }

    public enum Outcome {
        EXPIRED, SKIPPED
    }
}
