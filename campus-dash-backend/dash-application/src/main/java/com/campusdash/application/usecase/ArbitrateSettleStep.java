package com.campusdash.application.usecase;

import com.campusdash.domain.credit.model.CreditEvent;
import com.campusdash.domain.credit.model.CreditEventType;
import com.campusdash.domain.credit.ports.CreditRepository;
import com.campusdash.domain.errand.model.Errand;
import com.campusdash.domain.errand.model.ErrandStatus;
import com.campusdash.domain.errand.ports.ErrandRepository;
import com.campusdash.domain.wallet.model.*;
import com.campusdash.domain.wallet.ports.WalletRepository;
import com.campusdash.shared.Money;
import com.campusdash.shared.SnowflakeIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 仲裁裁给跑腿时的事务性资金步骤。
 *
 * 单独拆成一个 Bean 而不是写在 ArbitrateErrandUseCase 里，是为了避开
 * @Transactional 同类自调用失效——同一个坑 P1 用 GrabTransactionalStep 避过、
 * P2 在 TimeoutTransferUseCase 上重犯过一次，这里不再重复。
 */
@Component
public class ArbitrateSettleStep {

    private static final Logger log = LoggerFactory.getLogger(ArbitrateSettleStep.class);
    private static final long ESCROW_ACCOUNT_OWNER = -1L;
    private static final long COMMISSION_ACCOUNT_OWNER = -2L;

    private final ErrandRepository errandRepository;
    private final WalletRepository walletRepository;
    private final SnowflakeIdGenerator idGenerator;
    private final CreditRepository creditRepository;
    private final double commissionRate;

    public ArbitrateSettleStep(ErrandRepository errandRepository,
                              WalletRepository walletRepository,
                              SnowflakeIdGenerator idGenerator,
                              CreditRepository creditRepository,
                              @Value("${dash.settle.commission-rate:0.05}") double commissionRate) {
        this.errandRepository = errandRepository;
        this.walletRepository = walletRepository;
        this.idGenerator = idGenerator;
        this.creditRepository = creditRepository;
        this.commissionRate = commissionRate;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean settleFromDispute(long errandId, Errand errand, EscrowOrder escrow,
                                     String bizNo, long operatorId) {
        // 闸门 1：托管单 HELD -> RELEASED
        if (walletRepository.casEscrowStatus(errand.campusId(), errandId,
                EscrowOrder.EscrowStatus.HELD, EscrowOrder.EscrowStatus.RELEASED) == 0) {
            log.info("仲裁结算幂等：escrow 已非 HELD errandId={}", errandId);
            return false;
        }
        // 闸门 2：任务 DISPUTED -> SETTLED
        if (errandRepository.casSettleFromDispute(errandId, errand.version()) == 0) {
            log.info("仲裁结算幂等：errand 已非 DISPUTED errandId={}", errandId);
            return false;
        }
        errandRepository.appendStatusLog(errandId, ErrandStatus.DISPUTED, ErrandStatus.SETTLED,
                errand.round(), operatorId);

        long total = escrow.amount().cents();
        long commissionCents = (long) Math.floor(total * commissionRate);
        Money runnerAmount = Money.ofCents(total - commissionCents);
        Money commissionAmount = Money.ofCents(commissionCents);

        WalletAccount escrowAccount = walletRepository
                .findByOwner(ESCROW_ACCOUNT_OWNER, AccountType.ESCROW).orElseThrow();
        WalletAccount runnerAccount = walletRepository
                .findByOwner(errand.grabberId(), AccountType.USER).orElseThrow();
        WalletAccount commissionAccount = walletRepository
                .findByOwner(COMMISSION_ACCOUNT_OWNER, AccountType.COMMISSION).orElseThrow();

        walletRepository.casDebit(escrowAccount.id(), escrow.amount());
        walletRepository.casCredit(runnerAccount.id(), runnerAmount);
        walletRepository.casCredit(commissionAccount.id(), commissionAmount);

        // 闸门 3：bizNo 唯一索引
        walletRepository.insertLedger(new LedgerEntry(
                idGenerator.nextId(), bizNo, escrowAccount.id(), errand.publisherId(),
                LedgerEntry.Direction.DEBIT, escrow.amount(),
                escrowAccount.available().minus(escrow.amount()),
                LedgerEntry.RefType.SETTLE, errandId));
        walletRepository.insertLedger(new LedgerEntry(
                idGenerator.nextId(), bizNo, runnerAccount.id(), errand.grabberId(),
                LedgerEntry.Direction.CREDIT, runnerAmount,
                runnerAccount.available().plus(runnerAmount),
                LedgerEntry.RefType.SETTLE, errandId));
        if (commissionCents > 0) {
            walletRepository.insertLedger(new LedgerEntry(
                    idGenerator.nextId(), bizNo, commissionAccount.id(), COMMISSION_ACCOUNT_OWNER,
                    LedgerEntry.Direction.CREDIT, commissionAmount,
                    commissionAccount.available().plus(commissionAmount),
                    LedgerEntry.RefType.SETTLE, errandId));
        }
        // 仲裁支持跑腿：跑腿无过错，按正常结算计分（同事务）
        creditRepository.applyEvent(new CreditEvent(
                idGenerator.nextId(), CreditEvent.settleBizNo(errandId), errand.grabberId(),
                CreditEventType.SETTLE, CreditEventType.SETTLE.delta(), "ERRAND", errandId,
                java.time.Instant.now()));
        return true;
    }
}
