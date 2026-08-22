package com.campusdash.application.usecase;

import com.campusdash.domain.errand.model.ErrandStatus;
import com.campusdash.domain.errand.ports.ErrandRepository;
import com.campusdash.domain.grab.model.GrabRecord;
import com.campusdash.domain.grab.ports.GrabRecordRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 抢单的事务性步骤，单独成 Bean 是为了避开 @Transactional 的经典失效场景：
 * 同类内部方法自调用会绕过 Spring 代理，事务注解根本不生效。
 *
 * GrabErrandUseCase 里的 Redis 补偿必须在事务之外，
 * 所以事务边界只能包住"CAS 更新 + 写抢单记录 + 写状态日志"这三步。
 */
@Service
public class GrabTransactionalStep {

    private final ErrandRepository errandRepository;
    private final GrabRecordRepository grabRecordRepository;

    public GrabTransactionalStep(ErrandRepository errandRepository,
                                 GrabRecordRepository grabRecordRepository) {
        this.errandRepository = errandRepository;
        this.grabRecordRepository = grabRecordRepository;
    }

    /**
     * @return true 抢中，false 并发冲突（CAS 失败或撞唯一索引）
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean lockAndRecord(long campusId, long errandId, long runnerId, long expectedVersion,
                                 int seq, int round, long recordId, ErrandStatus fromStatus) {
        int affected = errandRepository.casLockForRunner(errandId, runnerId, expectedVersion);
        if (affected == 0) {
            // 状态已被别人改掉，说明这个名额被别人抢走了
            return false;
        }
        try {
            grabRecordRepository.insert(GrabRecord.grabbed(recordId, campusId, errandId, runnerId, seq, round));
        } catch (DuplicateKeyException e) {
            // 唯一索引兜底触发：抛出去让事务回滚，CAS 的更新也会一起撤销
            throw new DuplicateKeyException(
                    "抢单记录唯一索引冲突 errandId=" + errandId + " runnerId=" + runnerId + " seq=" + seq, e);
        }
        errandRepository.appendStatusLog(errandId, fromStatus, ErrandStatus.LOCKED, round, runnerId);
        return true;
    }
}
