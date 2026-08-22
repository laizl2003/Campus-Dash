package com.campusdash.application.usecase;

import com.campusdash.domain.errand.model.Errand;
import com.campusdash.domain.notify.ports.RealtimeNotifier;
import com.campusdash.domain.errand.model.ErrandStatus;
import com.campusdash.domain.errand.ports.ErrandRepository;
import com.campusdash.shared.BizException;
import com.campusdash.shared.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 发起争议用例：ACCEPTED / PICKED_UP / DELIVERED -> DISPUTED。
 *
 * 发单人与当前跑腿都能发起。托管资金在 DISPUTED 状态下既不结算也不退款，
 * 原地冻住等仲裁——这是资金的安全阀，避免"钱已给跑腿但发单人说没收到"的死局。
 * 所以本用例不碰任何资金，只改状态。
 */
@Service
public class DisputeErrandUseCase {

    private final CacheEvictSupport cacheEvict;
    private final ErrandRepository errandRepository;
    private final RealtimeNotifier notifier;

    public DisputeErrandUseCase(ErrandRepository errandRepository,
                                CacheEvictSupport cacheEvict,
                                RealtimeNotifier notifier) {
        this.errandRepository = errandRepository;
        this.cacheEvict = cacheEvict;
        this.notifier = notifier;
    }

    @Transactional(rollbackFor = Exception.class)
    public void raise(long errandId, long operatorId) {
        Errand errand = errandRepository.findById(errandId)
                .orElseThrow(() -> new BizException(ErrorCode.ERRAND_NOT_FOUND, "id=" + errandId));
        ErrandStatus from = errand.status();
        long versionBefore = errand.version();

        // 领域层校验"必须是当事双方之一"
        errand.raiseDispute(operatorId, versionBefore);

        int updated = errandRepository.casDispute(errandId, versionBefore);
        if (updated == 0) {
            throw new BizException(ErrorCode.STALE_VERSION, "发起争议失败，状态已变更 errandId=" + errandId);
        }
        errandRepository.appendStatusLog(errandId, from, ErrandStatus.DISPUTED, errand.round(), operatorId);
        notifier.errandStatusChanged(errand.id(), errand.publisherId(), errand.grabberId(),
                ErrandStatus.DISPUTED.name(), errand.round());

        // 状态变了，详情缓存必须失效。挂在事务提交后执行（见 CacheEvictSupport 的注释）
        cacheEvict.evictAfterCommit(errandId);
    }
}
