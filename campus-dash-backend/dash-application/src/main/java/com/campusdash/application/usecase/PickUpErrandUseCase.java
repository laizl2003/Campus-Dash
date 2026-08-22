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
 * 取货用例：ACCEPTED -> PICKED_UP。
 *
 * 这一步不碰钱，是纯状态流转，但必须有用例——P3 时因为缺它，
 * SettlementIT 只能用 SQL 直接改状态绕过，"全链路闭环"是有水分的。
 * 补上后前端才能真正走完整流程。
 */
@Service
public class PickUpErrandUseCase {

    private final CacheEvictSupport cacheEvict;
    private final ErrandRepository errandRepository;
    private final RealtimeNotifier notifier;

    public PickUpErrandUseCase(ErrandRepository errandRepository,
                               CacheEvictSupport cacheEvict,
                               RealtimeNotifier notifier) {
        this.errandRepository = errandRepository;
        this.cacheEvict = cacheEvict;
        this.notifier = notifier;
    }

    @Transactional(rollbackFor = Exception.class)
    public void pickUp(long errandId, long runnerId) {
        Errand errand = errandRepository.findById(errandId)
                .orElseThrow(() -> new BizException(ErrorCode.ERRAND_NOT_FOUND, "id=" + errandId));
        long versionBefore = errand.version();

        // 领域层校验"必须是当前跑腿本人"与流转合法性
        errand.pickUp(runnerId, versionBefore);

        int updated = errandRepository.casPickUp(errandId, runnerId, versionBefore);
        if (updated == 0) {
            throw new BizException(ErrorCode.STALE_VERSION, "取货失败，状态已变更 errandId=" + errandId);
        }
        errandRepository.appendStatusLog(errandId, ErrandStatus.ACCEPTED, ErrandStatus.PICKED_UP,
                errand.round(), runnerId);
        notifier.errandStatusChanged(errand.id(), errand.publisherId(), errand.grabberId(),
                ErrandStatus.PICKED_UP.name(), errand.round());

        // 状态变了，详情缓存必须失效。挂在事务提交后执行（见 CacheEvictSupport 的注释）
        cacheEvict.evictAfterCommit(errandId);
    }
}
