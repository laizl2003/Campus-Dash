package com.campusdash.application.usecase;

import com.campusdash.domain.credit.model.CreditEvent;
import com.campusdash.domain.credit.model.CreditEventType;
import com.campusdash.domain.credit.ports.CreditRepository;
import com.campusdash.domain.errand.model.Errand;
import com.campusdash.domain.errand.model.ErrandStatus;
import com.campusdash.domain.errand.ports.DelayMessagePort;
import com.campusdash.domain.errand.ports.ErrandRepository;
import com.campusdash.domain.errand.ports.LocalMessageRepository;
import com.campusdash.domain.grab.ports.CandidateQueuePort;
import com.campusdash.domain.grab.ports.GrabSlotPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 确认超时自动流转：抢中者限时未确认，任务转给候选队列下一位；候选空则回退重新开放。
 *
 * 幂等三件套（架构文档 7.4）：
 *   1. version CAS —— 任务被任何操作改动过，旧消息的 CAS 必然失败
 *   2. round 轮次校验 —— 区分"消息是第几轮发出的"，防 A→B→A 轮回时旧消息误判
 *   3. local_message.msg_key 唯一索引 —— 同一轮的消息只登记一次
 *
 * 消息一定会重复投递（MQ 语义是 at-least-once），所以每个动作都必须可重复执行。
 *
 * 本类刻意不加 @Transactional：Redis 操作不受数据库事务保护，
 * 把它们圈进事务只会拉长事务、放大锁竞争。事务边界收在 TimeoutTransferStep 里。
 */
@Service
public class TimeoutTransferUseCase {

    private static final Logger log = LoggerFactory.getLogger(TimeoutTransferUseCase.class);

    private final ErrandRepository errandRepository;
    private final CandidateQueuePort candidateQueue;
    private final GrabSlotPort grabSlotPort;
    private final DelayMessagePort delayMessagePort;
    private final LocalMessageRepository localMessageRepository;
    private final TimeoutTransferStep step;
    private final CacheEvictSupport cacheEvict;
    private final CreditRepository creditRepository;
    private final com.campusdash.shared.SnowflakeIdGenerator creditIdGenerator;
    private final long confirmTimeoutSeconds;
    /**
     * 最大流转轮次。
     *
     * 没有这个上限会出问题：一个爆款任务如果有 1999 个候选人，就会流转 1999 轮，
     * 每轮 5 分钟等于 7 天都结束不了，worker 一直在为它做无用功。
     * 实测中确实出现过 round 涨到 20+ 仍在继续的情况。
     * 达到上限后直接回退 PUBLISHED 让所有人重新抢，比继续挨个试更合理。
     */
    private final int maxTransferRounds;

    public TimeoutTransferUseCase(ErrandRepository errandRepository,
                                  CandidateQueuePort candidateQueue,
                                  GrabSlotPort grabSlotPort,
                                  DelayMessagePort delayMessagePort,
                                  LocalMessageRepository localMessageRepository,
                                  TimeoutTransferStep step,
                                  CacheEvictSupport cacheEvict,
                                  CreditRepository creditRepository,
                                  com.campusdash.shared.SnowflakeIdGenerator creditIdGenerator,
                                  @Value("${dash.timeout.confirm-seconds:300}") long confirmTimeoutSeconds,
                                  @Value("${dash.timeout.max-transfer-rounds:5}") int maxTransferRounds) {
        this.errandRepository = errandRepository;
        this.candidateQueue = candidateQueue;
        this.grabSlotPort = grabSlotPort;
        this.delayMessagePort = delayMessagePort;
        this.localMessageRepository = localMessageRepository;
        this.step = step;
        this.cacheEvict = cacheEvict;
        this.creditRepository = creditRepository;
        this.creditIdGenerator = creditIdGenerator;
        this.confirmTimeoutSeconds = confirmTimeoutSeconds;
        this.maxTransferRounds = maxTransferRounds;
    }

    public enum Outcome {
        /** 已流转给下一位候选人 */
        TRANSFERRED,
        /** 候选队列空，任务回退重新开放 */
        REVERTED,
        /** 消息已失效：任务已确认、已被处理或轮次不匹配（幂等生效） */
        SKIPPED
    }

    /**
     * 处理一条超时消息。
     *
     * @param expectedRound 消息携带的轮次，与当前 round 不符即判定为旧消息
     */
    public Outcome handleTimeout(long errandId, int expectedRound) {
        Errand errand = errandRepository.findById(errandId).orElse(null);
        if (errand == null) {
            return Outcome.SKIPPED;
        }
        // 幂等第二件：状态或轮次不匹配，说明任务已被确认/已流转过，这条是旧消息
        if (errand.status() != ErrandStatus.LOCKED || errand.round() != expectedRound) {
            log.debug("超时消息已失效 errandId={} status={} round={} expectedRound={}",
                    errandId, errand.status(), errand.round(), expectedRound);
            return Outcome.SKIPPED;
        }

        // 超过最大流转轮次：不再逐个试候选人，直接回退让所有人重新抢
        if (errand.round() >= maxTransferRounds) {
            log.info("流转轮次已达上限 errandId={} round={} max={}，回退重新开放",
                    errandId, errand.round(), maxTransferRounds);
            return revertAndReturnSlot(errand);
        }

        Optional<Long> next = candidateQueue.pollBest(errandId);
        if (next.isPresent()) {
            long nextRunner = next.get();
            var result = step.transfer(errand, nextRunner, confirmTimeoutSeconds);
            if (!result.applied()) {
                // CAS 失败：任务刚被确认或被别的消费者处理了。
                // 候选人已从 ZSET 弹出，放回去避免他丢失排队资格。
                candidateQueue.offer(errandId, nextRunner, System.currentTimeMillis());
                return Outcome.SKIPPED;
            }
            dispatch(result.pendingSend());
            log.info("任务已流转 errandId={} round={} -> nextRunner={}",
                    errandId, expectedRound + 1, nextRunner);
            return Outcome.TRANSFERRED;
        }

        log.info("候选队列为空，任务回退重新开放 errandId={}", errandId);
        return revertAndReturnSlot(errand);
    }

    /** 回退到 PUBLISHED 并把 Redis 名额还回去，两处都要做否则任务显示可抢但谁都抢不到 */
    private Outcome revertAndReturnSlot(Errand errand) {
        var result = step.revert(errand);
        if (!result.applied()) {
            return Outcome.SKIPPED;
        }
        Long grabber = errand.grabberId();
        if (grabber != null) {
            grabSlotPort.rollback(errand.id(), grabber, "revert:" + errand.round());
            // 抢中后超时未确认：信用扣分。biz_no 带 round 保证同一轮不重复扣分。
            // 这里不在业务事务内（revert 事务已提交），applyEvent 自身原子；
            // 记录失败不影响回退结果，信用分容忍度高于资金
            creditRepository.applyEvent(new CreditEvent(
                    creditIdGenerator.nextId(),
                    CreditEvent.revertBizNo(errand.id(), errand.round()),
                    grabber, CreditEventType.GRAB_TIMEOUT_REVERT,
                    CreditEventType.GRAB_TIMEOUT_REVERT.delta(), "ERRAND", errand.id(),
                    java.time.Instant.now()));
        }
        // 回退到 PUBLISHED、名额归还，详情缓存必须失效
        cacheEvict.evictAfterCommit(errand.id());
        return Outcome.REVERTED;
    }

    /** 抢单成功后登记首轮超时消息，供 GrabErrandUseCase 在事务提交后调用 */
    public void scheduleFirstTimeout(long errandId, int round, long version) {
        dispatch(step.enqueueTimeout(errandId, round, version, confirmTimeoutSeconds));
    }

    /**
     * 事务提交后再发 MQ。
     * 发送失败不抛出：消息已在 local_message 里是 PENDING 状态，
     * worker 的重发 job 会兜底，业务不受影响。
     */
    private void dispatch(TimeoutTransferStep.PendingSend send) {
        if (send == null) {
            return;
        }
        try {
            delayMessagePort.send(send.topic(), send.msgKey(), send.payload(), send.deliverAt());
            localMessageRepository.markSent(send.msgKey());
        } catch (RuntimeException e) {
            log.warn("超时消息发送失败，留待 worker 重发 msgKey={}", send.msgKey(), e);
        }
    }

    public long confirmTimeoutSeconds() {
        return confirmTimeoutSeconds;
    }
}
