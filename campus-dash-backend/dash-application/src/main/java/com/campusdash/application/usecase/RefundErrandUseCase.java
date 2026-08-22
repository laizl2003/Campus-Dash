package com.campusdash.application.usecase;

import com.campusdash.domain.notify.ports.RealtimeNotifier;
import com.campusdash.domain.errand.model.Errand;
import com.campusdash.domain.errand.model.ErrandStatus;
import com.campusdash.domain.errand.ports.ErrandRepository;
import com.campusdash.domain.wallet.model.*;
import com.campusdash.domain.wallet.ports.FundAuditPort;
import com.campusdash.domain.wallet.ports.FundEventPort;
import com.campusdash.domain.wallet.ports.FundEventPort.FundEvent;
import com.campusdash.domain.wallet.ports.WalletRepository;
import com.campusdash.shared.BizException;
import com.campusdash.shared.ErrorCode;
import com.campusdash.shared.Money;
import com.campusdash.shared.SnowflakeIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 退款用例。两种触发场景：
 *   1. 发单人取消（DRAFT/PUBLISHED → CANCELLED）
 *   2. 仲裁支持发单人（DISPUTED → REFUNDED）
 *
 * 资金流向：托管账户 → 发单人账户，全额退回，无佣金。
 */
@Service
public class RefundErrandUseCase {

    private static final Logger log = LoggerFactory.getLogger(RefundErrandUseCase.class);
    private static final long ESCROW_ACCOUNT_OWNER = -1L;

    private final ErrandRepository errandRepository;
    private final WalletRepository walletRepository;
    private final FundAuditPort auditPort;
    private final FundEventPort fundEventPort;
    private final SnowflakeIdGenerator idGenerator;
    private final CacheEvictSupport cacheEvict;
    private final TransactionTemplate transactionTemplate;
    private final RealtimeNotifier notifier;

    public RefundErrandUseCase(ErrandRepository errandRepository,
                               WalletRepository walletRepository,
                               FundAuditPort auditPort,
                               FundEventPort fundEventPort,
                               SnowflakeIdGenerator idGenerator,
                               CacheEvictSupport cacheEvict,
                               TransactionTemplate transactionTemplate,
                               RealtimeNotifier notifier) {
        this.errandRepository = errandRepository;
        this.walletRepository = walletRepository;
        this.auditPort = auditPort;
        this.fundEventPort = fundEventPort;
        this.idGenerator = idGenerator;
        this.cacheEvict = cacheEvict;
        this.transactionTemplate = transactionTemplate;
        this.notifier = notifier;
    }

    public enum Result { REFUNDED, ALREADY_REFUNDED, CONFLICT }

    /** 发单人主动取消 */
    public Result cancelAndRefund(long errandId, long publisherId) {
        Errand errand = errandRepository.findById(errandId)
                .orElseThrow(() -> new BizException(ErrorCode.ERRAND_NOT_FOUND, "id=" + errandId));
        if (errand.publisherId() != publisherId) {
            throw new BizException(ErrorCode.NOT_PUBLISHER, "actor=" + publisherId);
        }
        // 领域层校验状态。from 必须在流转前取——领域对象流转后 status() 已是 CANCELLED，
        // 那时再记日志会得到 CANCELLED->CANCELLED 的脏记录（P4 实测发现的 bug）
        ErrandStatus from = errand.status();
        errand.cancelByPublisher(publisherId, errand.version());

        int cancelled = errandRepository.casCancel(errandId, errand.version() - 1);
        if (cancelled == 0) {
            return Result.CONFLICT;
        }
        errandRepository.appendStatusLog(errandId, from, ErrandStatus.CANCELLED, errand.round(), publisherId);
        return doRefund(errandId, errand, publisherId);
    }

    /** 仲裁支持发单人 */
    public Result arbitrateRefund(long errandId, long operatorId) {
        Errand errand = errandRepository.findById(errandId)
                .orElseThrow(() -> new BizException(ErrorCode.ERRAND_NOT_FOUND, "id=" + errandId));
        if (errand.status() != ErrandStatus.DISPUTED) {
            throw new BizException(ErrorCode.NOT_ARBITRABLE, "status=" + errand.status());
        }
        int updated = errandRepository.casRefundFromDispute(errandId, errand.version());
        if (updated == 0) return Result.CONFLICT;
        errandRepository.appendStatusLog(errandId, ErrandStatus.DISPUTED, ErrandStatus.REFUNDED, errand.round(), operatorId);
        return doRefund(errandId, errand, operatorId);
    }

    private Result doRefund(long errandId, Errand errand, long operatorId) {
        EscrowOrder escrow = walletRepository.findEscrowByErrandId(errand.campusId(), errandId)
                .orElseThrow(() -> new BizException(ErrorCode.ESCROW_NOT_FOUND, "errandId=" + errandId));
        if (escrow.status() != EscrowOrder.EscrowStatus.HELD) {
            return Result.ALREADY_REFUNDED;
        }

        String bizNo = LedgerEntry.refundBizNo(errandId);
        FundEvent event = new FundEvent(bizNo, "REFUNDED", errandId, errand.publisherId(),
                errand.grabberId() != null ? errand.grabberId() : 0L, escrow.amount().cents(), 0);

        // 与结算同理：lambda 自调用下 @Transactional 不生效（P3 遗留隐患，P5 修复），
        // 改用程序化事务保证退款动作的原子性
        boolean committed = fundEventPort.publishInTransaction(event, () ->
                Boolean.TRUE.equals(transactionTemplate.execute(status ->
                        doRefundInTx(errand.campusId(), errandId, escrow, bizNo, operatorId))));
        if (committed) {
            cacheEvict.evictAfterCommit(errandId);
            notifier.errandStatusChanged(errandId, errand.publisherId(), errand.grabberId(),
                    errand.status() == com.campusdash.domain.errand.model.ErrandStatus.CANCELLED
                            ? "CANCELLED" : "REFUNDED", errand.round());
            auditPort.record(bizNo, "REFUND", errandId, operatorId,
                    String.format("{\"amount\":%d}", escrow.amount().cents()), true, null);
            return Result.REFUNDED;
        }
        return Result.CONFLICT;
    }

    /** 退款事务体，由 TransactionTemplate 包裹（见 doRefund 注释） */
    boolean doRefundInTx(long campusId, long errandId, EscrowOrder escrow, String bizNo, long operatorId) {
        int escrowed = walletRepository.casEscrowStatus(campusId, errandId,
                EscrowOrder.EscrowStatus.HELD, EscrowOrder.EscrowStatus.REFUNDED);
        if (escrowed == 0) return false;

        WalletAccount escrowAccount = walletRepository.findByOwner(ESCROW_ACCOUNT_OWNER, AccountType.ESCROW).orElseThrow();
        WalletAccount publisherAccount = walletRepository.findByOwner(escrow.publisherId(), AccountType.USER).orElseThrow();

        walletRepository.casDebit(escrowAccount.id(), escrow.amount());
        walletRepository.casCredit(publisherAccount.id(), escrow.amount());

        walletRepository.insertLedger(new LedgerEntry(
                idGenerator.nextId(), bizNo, escrowAccount.id(), escrow.publisherId(),
                LedgerEntry.Direction.DEBIT, escrow.amount(),
                escrowAccount.available().minus(escrow.amount()),
                LedgerEntry.RefType.REFUND, errandId));
        walletRepository.insertLedger(new LedgerEntry(
                idGenerator.nextId(), bizNo, publisherAccount.id(), escrow.publisherId(),
                LedgerEntry.Direction.CREDIT, escrow.amount(),
                publisherAccount.available().plus(escrow.amount()),
                LedgerEntry.RefType.REFUND, errandId));
        return true;
    }
}
