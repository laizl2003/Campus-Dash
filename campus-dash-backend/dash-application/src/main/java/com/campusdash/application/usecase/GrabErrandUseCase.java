package com.campusdash.application.usecase;

import com.campusdash.domain.credit.ports.CreditRepository;
import com.campusdash.domain.errand.model.Errand;
import com.campusdash.domain.errand.model.ErrandStatus;
import com.campusdash.domain.errand.ports.ErrandQueryPort;
import com.campusdash.domain.errand.ports.ErrandRepository;
import com.campusdash.domain.notify.ports.RealtimeNotifier;
import com.campusdash.domain.grab.model.SlotOutcome;
import com.campusdash.domain.grab.ports.CandidateQueuePort;
import com.campusdash.domain.grab.ports.GrabRateLimiterPort;
import com.campusdash.domain.grab.ports.GrabSlotPort;
import com.campusdash.shared.ErrorCode;
import com.campusdash.shared.SnowflakeIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 抢单用例——整个项目的心脏。
 *
 * 四层防护（架构文档 6.3）：
 *   L1 限流         —— Sentinel 热点参数限流，P7 引入
 *   L2 资格前置     —— 信用分门槛与在途任务数，P5 引入完整规则
 *   L3 Redis Lua    —— 原子判定：状态校验 + 名额扣减 + 抢中者写入 + 幂等去重
 *   L4 MySQL CAS    —— 最终裁决：状态与版本号双条件 + grab_record 双唯一索引
 *
 * 关键设计：L3 成功但 L4 失败时必须回滚 Redis 名额，否则名额永久泄漏。
 * 补偿动作放在事务外执行——事务已经回滚了，补偿是独立动作。
 */
@Service
public class GrabErrandUseCase {

    private static final Logger log = LoggerFactory.getLogger(GrabErrandUseCase.class);

    private final GrabSlotPort grabSlotPort;
    private final CandidateQueuePort candidateQueue;
    private final ErrandRepository errandRepository;
    private final GrabTransactionalStep transactionalStep;
    private final SnowflakeIdGenerator idGenerator;
    private final CacheEvictSupport cacheEvict;
    /** P2 起：抢中后要给抢中者一个确认窗口，超时则流转给候选人 */
    private final TimeoutTransferUseCase timeoutTransferUseCase;
    /** P5 起：资格校验读后端信用分，不再信任客户端传参 */
    private final CreditRepository creditRepository;
    private final ErrandQueryPort errandQueryPort;
    private final int minCreditScore;
    private final int maxOngoing;
    private final RealtimeNotifier notifier;
    private final GrabRateLimiterPort rateLimiter;

    public GrabErrandUseCase(GrabSlotPort grabSlotPort,
                             CandidateQueuePort candidateQueue,
                             ErrandRepository errandRepository,
                             GrabTransactionalStep transactionalStep,
                             SnowflakeIdGenerator idGenerator,
                             TimeoutTransferUseCase timeoutTransferUseCase,
                              CacheEvictSupport cacheEvict,
                              CreditRepository creditRepository,
                              ErrandQueryPort errandQueryPort,
                              @org.springframework.beans.factory.annotation.Value("${dash.credit.min-score:40}") int minCreditScore,
                              @org.springframework.beans.factory.annotation.Value("${dash.credit.max-ongoing:5}") int maxOngoing,
                              RealtimeNotifier notifier,
                              GrabRateLimiterPort rateLimiter) {
        this.grabSlotPort = grabSlotPort;
        this.candidateQueue = candidateQueue;
        this.errandRepository = errandRepository;
        this.transactionalStep = transactionalStep;
        this.idGenerator = idGenerator;
        this.timeoutTransferUseCase = timeoutTransferUseCase;
        this.cacheEvict = cacheEvict;
        this.creditRepository = creditRepository;
        this.errandQueryPort = errandQueryPort;
        this.minCreditScore = minCreditScore;
        this.maxOngoing = maxOngoing;
        this.notifier = notifier;
        this.rateLimiter = rateLimiter;
    }

    /**
     * creditScore 参数自 P5 起被忽略：信用分改由后端从 credit_score 读取，
     * 客户端传的分值不可信（伪造高分就能绕过资格门槛、垄断流转机会）。
     * 保留该参数只为兼容既有调用，新代码请用三参构造器。
     */
    public record Command(long errandId, long runnerId, String requestId, int creditScore) {
        public Command(long errandId, long runnerId, String requestId) {
            this(errandId, runnerId, requestId, 0);
        }
    }

    public record Result(ErrorCode code, boolean grabbed, Long candidateRank) {
        static Result success() {
            return new Result(ErrorCode.OK, true, null);
        }
        static Result failed(ErrorCode code, Long rank) {
            return new Result(code, false, rank);
        }
    }

    public Result grab(Command cmd) {
        // L1：热点任务限流。放在所有下游调用之前，避免过热任务继续消耗 Redis 与 DB。
        if (!rateLimiter.tryPass(cmd.errandId(), cmd.runnerId())) {
            return Result.failed(ErrorCode.GRAB_RATE_LIMITED, null);
        }

        // L2：资格前置校验（P5 起读后端数据，不再信任客户端传的 creditScore）。
        // 放在 Lua 判定之前：没资格的人连名额裁决都不该参与，省 Redis 压力
        int score = creditRepository.scoreOf(cmd.runnerId());
        if (score < minCreditScore) {
            return Result.failed(ErrorCode.CREDIT_TOO_LOW, null);
        }
        if (errandQueryPort.countOngoingByRunner(cmd.runnerId()) >= maxOngoing) {
            return Result.failed(ErrorCode.TOO_MANY_ONGOING, null);
        }

        // L3：Redis Lua 原子判定。绝大多数失败请求在这一步就被挡住，不会打到数据库
        SlotOutcome outcome = grabSlotPort.tryAcquire(cmd.errandId(), cmd.runnerId(), cmd.requestId());

        switch (outcome) {
            case SLOT_FULL -> {
                return Result.failed(ErrorCode.SLOT_FULL, enqueueCandidate(cmd));
            }
            case ALREADY_GRABBED -> {
                return Result.failed(ErrorCode.ALREADY_GRABBED, null);
            }
            case DUPLICATE_REQUEST -> {
                // 幂等：同一 requestId 重复提交，返回首次的成功结果，不重复占名额
                return Result.success();
            }
            case NOT_GRABBABLE -> {
                return Result.failed(ErrorCode.ERRAND_NOT_GRABBABLE, null);
            }
            case ACQUIRED -> {
                // 继续走 L4
            }
        }

        // L4：数据库 CAS 落库，最终裁决
        try {
            Errand errand = errandRepository.findById(cmd.errandId()).orElse(null);
            if (errand == null) {
                grabSlotPort.rollback(cmd.errandId(), cmd.runnerId(), cmd.requestId());
                return Result.failed(ErrorCode.ERRAND_NOT_FOUND, null);
            }

            int seq = errand.slotTaken() + 1;
            boolean ok = transactionalStep.lockAndRecord(
                    errand.campusId(), cmd.errandId(), cmd.runnerId(), errand.version(), seq, errand.round(),
                    idGenerator.nextId(), errand.status());
            if (!ok) {
                // CAS 冲突或唯一索引冲突：Redis 已扣名额，必须补回去
                grabSlotPort.rollback(cmd.errandId(), cmd.runnerId(), cmd.requestId());
                return Result.failed(ErrorCode.GRAB_CONFLICT, enqueueCandidate(cmd));
            }
            // 抢中成功，给这一轮开启确认窗口：超时未确认则自动流转给候选人。
            // 放在事务提交之后：登记本身在 Step 的独立事务里，发送是外部 IO 不该进业务事务。
            timeoutTransferUseCase.scheduleFirstTimeout(
                    cmd.errandId(), errand.round(), errand.version() + 1);
            // 状态 PUBLISHED -> LOCKED、slotTaken+1，详情缓存必须失效。
            // 只在真正抢中时失效；上面 DUPLICATE_REQUEST 的幂等重放不改状态，无需失效
            cacheEvict.evictAfterCommit(cmd.errandId());
            // 实时推送给发单人（抢单者自己已知结果）
            Errand e2 = errandRepository.findById(cmd.errandId()).orElse(null);
            if (e2 != null) {
                notifier.errandStatusChanged(e2.id(), e2.publisherId(), e2.grabberId(),
                        e2.status().name(), e2.round());
            }
            return Result.success();
        } catch (RuntimeException e) {
            // 任何未预期异常都要回滚 Redis 名额，否则名额泄漏，任务永久卡在"已被抢"
            grabSlotPort.rollback(cmd.errandId(), cmd.runnerId(), cmd.requestId());
            log.warn("抢单落库异常，已回滚 Redis 名额 errandId={} runnerId={}", cmd.errandId(), cmd.runnerId(), e);
            return Result.failed(ErrorCode.GRAB_CONFLICT, null);
        }
    }

    /**
     * 抢单失败者进候选队列，为 P2 的超时流转做准备。
     * score 越小越优先：用时间戳减去信用分加权，让高信用用户略微占先，
     * 加权上限避免高信用用户完全垄断流转机会。
     */
    private Long enqueueCandidate(Command cmd) {
        // 信用分权重从后端读（入口处已查过一次，这里再查是为拿最新值——
        // 单主键查询成本极低，换来的是不依赖入口快照的正确性）
        int credit = creditRepository.scoreOf(cmd.runnerId());
        double creditBonus = Math.min(credit, 100) * 10.0;
        double score = System.currentTimeMillis() - creditBonus;
        candidateQueue.offer(cmd.errandId(), cmd.runnerId(), score);
        return candidateQueue.size(cmd.errandId());
    }
}
