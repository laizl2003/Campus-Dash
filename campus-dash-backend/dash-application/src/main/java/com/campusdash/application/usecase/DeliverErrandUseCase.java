package com.campusdash.application.usecase;

import com.campusdash.domain.errand.model.Errand;
import com.campusdash.domain.notify.ports.RealtimeNotifier;
import com.campusdash.domain.errand.model.ErrandStatus;
import com.campusdash.domain.errand.ports.ErrandRepository;
import com.campusdash.domain.errand.ports.LocalMessageRepository;
import com.campusdash.shared.BizException;
import com.campusdash.shared.ErrorCode;
import com.campusdash.shared.SnowflakeIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 送达用例：PICKED_UP -> DELIVERED。
 * 同时通过本地消息表登记一条延迟消息，到期触发自动结算。
 *
 * 为什么用本地消息表而不是事务消息：
 * 自动结算需要"24 小时后投递"这个延迟，而事务消息不支持 setDeliveryTimestamp。
 * 需要"延迟 + 与事务一致"时，本地消息表是唯一选择。
 */
@Service
public class DeliverErrandUseCase {

    private static final Logger log = LoggerFactory.getLogger(DeliverErrandUseCase.class);

    private final ErrandRepository errandRepository;
    private final LocalMessageRepository localMessageRepository;
    private final SnowflakeIdGenerator idGenerator;
    private final CacheEvictSupport cacheEvict;
    private final RealtimeNotifier notifier;
    private final long autoSettleSeconds;

    public DeliverErrandUseCase(ErrandRepository errandRepository,
                                LocalMessageRepository localMessageRepository,
                                SnowflakeIdGenerator idGenerator,
                                CacheEvictSupport cacheEvict,
                                RealtimeNotifier notifier,
                                @Value("${dash.settle.auto-settle-seconds:86400}") long autoSettleSeconds) {
        this.errandRepository = errandRepository;
        this.localMessageRepository = localMessageRepository;
        this.idGenerator = idGenerator;
        this.cacheEvict = cacheEvict;
        this.notifier = notifier;
        this.autoSettleSeconds = autoSettleSeconds;
    }

    @Transactional(rollbackFor = Exception.class)
    public void deliver(long errandId, long runnerId) {
        Errand errand = errandRepository.findById(errandId)
                .orElseThrow(() -> new BizException(ErrorCode.ERRAND_NOT_FOUND, "id=" + errandId));
        // 领域校验
        errand.deliver(runnerId, errand.version(), Instant.now());

        int updated = errandRepository.casDeliver(errandId, runnerId, errand.version() - 1);
        if (updated == 0) {
            throw new BizException(ErrorCode.STALE_VERSION, "deliver failed");
        }
        errandRepository.appendStatusLog(errandId, ErrandStatus.PICKED_UP, ErrandStatus.DELIVERED,
                errand.round(), runnerId);

        // 状态变了，详情缓存失效（事务提交后执行）
        cacheEvict.evictAfterCommit(errandId);
        notifier.errandStatusChanged(errand.id(), errand.publisherId(), errand.grabberId(),
                ErrandStatus.DELIVERED.name(), errand.round());

        // 登记 24h 自动结算的延迟消息（本地消息表保证"事务成功则消息必发"）
        String msgKey = DelayTaskPolicy.autoSettleKey(errandId);
                String payload = DelayTaskPolicy.autoSettlePayload(errandId);
        Instant deliverAt = Instant.now().plusSeconds(autoSettleSeconds);
        boolean fresh = localMessageRepository.enqueue(
                idGenerator.nextId(), msgKey, DelayTaskPolicy.TOPIC_AUTO_SETTLE, payload, deliverAt);
        if (fresh) {
            log.info("已登记自动结算延迟消息 errandId={} deliverAt={}", errandId, deliverAt);
        }
    }
}
