package com.campusdash.application.usecase;

import com.campusdash.domain.auth.model.UserAccount;
import com.campusdash.domain.auth.ports.UserRepository;
import com.campusdash.domain.credit.model.CreditEvent;
import com.campusdash.domain.credit.model.CreditEventType;
import com.campusdash.domain.credit.ports.CreditRankingPort;
import com.campusdash.domain.credit.ports.CreditRepository;
import com.campusdash.domain.notify.ports.RealtimeNotifier;
import com.campusdash.domain.errand.model.Errand;
import com.campusdash.domain.errand.model.ErrandStatus;
import com.campusdash.domain.errand.ports.ErrandRepository;
import com.campusdash.domain.wallet.model.EscrowOrder;
import com.campusdash.domain.wallet.model.LedgerEntry;
import com.campusdash.domain.wallet.ports.FundAuditPort;
import com.campusdash.domain.wallet.ports.FundEventPort;
import com.campusdash.domain.wallet.ports.WalletRepository;
import com.campusdash.shared.BizException;
import com.campusdash.shared.ErrorCode;
import com.campusdash.shared.SnowflakeIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

/**
 * 仲裁用例：DISPUTED -> SETTLED（支持跑腿）或 DISPUTED -> REFUNDED（支持发单人）。
 *
 * 只做全额分账两种结果，不做按比例部分分账——比例分账会牵出
 * "佣金怎么算""余数归谁""退多少算合理"一堆边界，而教学价值增量很小。
 *
 * 支持发单人的分支直接复用 RefundErrandUseCase.arbitrateRefund；
 * 支持跑腿的分支需要单独实现，因为它的入口状态是 DISPUTED 而不是 DELIVERED，
 * SettleErrandUseCase 的 casSettle 只认 DELIVERED。
 */
@Service
public class ArbitrateErrandUseCase {

    private static final Logger log = LoggerFactory.getLogger(ArbitrateErrandUseCase.class);

    private final ErrandRepository errandRepository;
    private final WalletRepository walletRepository;
    private final RefundErrandUseCase refundUseCase;
    private final ArbitrateSettleStep settleStep;
    private final FundAuditPort auditPort;
    private final FundEventPort fundEventPort;
    private final CacheEvictSupport cacheEvict;
    private final CreditRepository creditRepository;
    private final CreditRankingPort creditRankingPort;
    private final SnowflakeIdGenerator idGenerator;
    private final RealtimeNotifier notifier;
    private final UserRepository users;
    private final String arbitratorId;

    public ArbitrateErrandUseCase(ErrandRepository errandRepository,
                                  WalletRepository walletRepository,
                                  RefundErrandUseCase refundUseCase,
                                  ArbitrateSettleStep settleStep,
                                  FundAuditPort auditPort,
                                  FundEventPort fundEventPort,
                                  CacheEvictSupport cacheEvict,
                                  CreditRepository creditRepository,
                                  CreditRankingPort creditRankingPort,
                                  SnowflakeIdGenerator idGenerator,
                                  RealtimeNotifier notifier,
                                  UserRepository users,
                                  @Value("${dash.auth.arbitrator-id:9001}") String arbitratorId) {
        this.errandRepository = errandRepository;
        this.walletRepository = walletRepository;
        this.refundUseCase = refundUseCase;
        this.settleStep = settleStep;
        this.auditPort = auditPort;
        this.fundEventPort = fundEventPort;
        this.cacheEvict = cacheEvict;
        this.creditRepository = creditRepository;
        this.creditRankingPort = creditRankingPort;
        this.idGenerator = idGenerator;
        this.notifier = notifier;
        this.users = users;
        this.arbitratorId = arbitratorId;
    }

    /** 仲裁裁决方向 */
    public enum Favor { RUNNER, PUBLISHER }

    public enum Result { SETTLED_TO_RUNNER, REFUNDED_TO_PUBLISHER, ALREADY_DONE, CONFLICT }

    public Result arbitrate(long errandId, Favor favor, String operatorId) {
        // 两种身份可以裁决：配置的仲裁员（演示号 9001）或 role=Admin 的账号（001）
        boolean allowed = arbitratorId.equals(operatorId)
                || users.findById(operatorId).map(UserAccount::isAdmin).orElse(false);
        if (!allowed) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "只有仲裁员可以裁决，actor=" + operatorId);
        }
        Errand errand = errandRepository.findById(errandId)
                .orElseThrow(() -> new BizException(ErrorCode.ERRAND_NOT_FOUND, "id=" + errandId));
        if (errand.status() != ErrandStatus.DISPUTED) {
            // 已经裁决过了（SETTLED/REFUNDED 都是终态）
            if (errand.status() == ErrandStatus.SETTLED || errand.status() == ErrandStatus.REFUNDED) {
                return Result.ALREADY_DONE;
            }
            throw new BizException(ErrorCode.NOT_ARBITRABLE, "status=" + errand.status());
        }

        if (favor == Favor.PUBLISHER) {
            var r = refundUseCase.arbitrateRefund(errandId, operatorId);
            if (r == RefundErrandUseCase.Result.REFUNDED && errand.grabberId() != null) {
                // 仲裁支持发单人 = 跑腿败诉：扣信用分。
                // 退款事务已提交，这里单独记事件（applyEvent 自身是原子的，
                // 极端情况下记录失败由对账与人工兜底——信用分不是资金，容忍度不同）
                creditRepository.applyEvent(new CreditEvent(
                        idGenerator.nextId(),
                        CreditEvent.disputeLoseBizNo(errandId, errand.grabberId()),
                        errand.grabberId(), CreditEventType.DISPUTE_LOSE,
                        CreditEventType.DISPUTE_LOSE.delta(), "ERRAND", errandId,
                        java.time.Instant.now()));
            }
            if (r == RefundErrandUseCase.Result.REFUNDED) {
                long refundAmount = walletRepository.findEscrowByErrandId(errand.campusId(), errandId)
                        .map(e -> e.amount().cents()).orElse(0L);
                auditPort.record(LedgerEntry.refundBizNo(errandId), "ARBITRATE", errandId, operatorId,
                        String.format("{\"favor\":\"PUBLISHER\",\"amount\":%d}", refundAmount),
                        true, null);
                notifier.errandStatusChanged(errandId, errand.publisherId(), errand.grabberId(),
                        "REFUNDED", errand.round());
                notifier.adminDisputeChanged(errandId, "REFUNDED");
                if (errand.grabberId() != null) {
                    int score = creditRepository.scoreOf(errand.grabberId());
                    creditRankingPort.update(errand.campusId(), errand.grabberId(), score);
                    notifier.creditChanged(errand.grabberId(), score,
                            CreditEventType.DISPUTE_LOSE.delta(), "争议败诉");
                }
            }
            return r == RefundErrandUseCase.Result.REFUNDED
                    ? Result.REFUNDED_TO_PUBLISHER
                    : (r == RefundErrandUseCase.Result.ALREADY_REFUNDED ? Result.ALREADY_DONE : Result.CONFLICT);
        }

        // 支持跑腿：走与结算相同的资金分配，但入口状态是 DISPUTED
        EscrowOrder escrow = walletRepository.findEscrowByErrandId(errand.campusId(), errandId)
                .orElseThrow(() -> new BizException(ErrorCode.ESCROW_NOT_FOUND, "errandId=" + errandId));
        if (escrow.status() != EscrowOrder.EscrowStatus.HELD) {
            return Result.ALREADY_DONE;
        }

        String bizNo = LedgerEntry.settleBizNo(errandId);
        var event = new FundEventPort.FundEvent(bizNo, "ARBITRATED", errandId, errand.publisherId(),
                errand.grabberId() == null ? "" : errand.grabberId(), escrow.amount().cents(), 0);

        boolean committed = fundEventPort.publishInTransaction(event,
                () -> settleStep.settleFromDispute(errandId, errand, escrow, bizNo, operatorId));

        if (committed) {
            cacheEvict.evictAfterCommit(errandId);
            auditPort.record(bizNo, "ARBITRATE", errandId, operatorId,
                    String.format("{\"favor\":\"RUNNER\",\"amount\":%d}", escrow.amount().cents()),
                    true, null);
            notifier.errandStatusChanged(errandId, errand.publisherId(), errand.grabberId(),
                    "SETTLED", errand.round());
            notifier.adminDisputeChanged(errandId, "SETTLED");
            int delta = CreditEventType.SETTLE.delta();
            int publisherScore = creditRepository.scoreOf(errand.publisherId());
            creditRankingPort.update(errand.campusId(), errand.publisherId(), publisherScore);
            notifier.creditChanged(errand.publisherId(), publisherScore, delta, "仲裁结算");
            if (errand.grabberId() != null) {
                int runnerScore = creditRepository.scoreOf(errand.grabberId());
                creditRankingPort.update(errand.campusId(), errand.grabberId(), runnerScore);
                notifier.creditChanged(errand.grabberId(), runnerScore, delta, "仲裁结算");
            }
            return Result.SETTLED_TO_RUNNER;
        }
        return Result.CONFLICT;
    }
}
