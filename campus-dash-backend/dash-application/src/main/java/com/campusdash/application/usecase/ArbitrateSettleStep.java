package com.campusdash.application.usecase;

import com.campusdash.domain.credit.model.CreditEvent;
import com.campusdash.domain.credit.model.CreditEventType;
import com.campusdash.domain.credit.ports.CreditRepository;
import com.campusdash.domain.errand.model.Errand;
import com.campusdash.domain.errand.model.ErrandStatus;
import com.campusdash.domain.errand.ports.ErrandRepository;
import com.campusdash.domain.voucher.model.VoucherUse;
import com.campusdash.domain.voucher.ports.VoucherRepository;
import com.campusdash.domain.wallet.model.*;
import com.campusdash.domain.wallet.ports.WalletRepository;
import com.campusdash.shared.BizException;
import com.campusdash.shared.ErrorCode;
import com.campusdash.shared.Money;
import com.campusdash.shared.SnowflakeIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

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
    private static final String ESCROW_ACCOUNT_OWNER = "-1";
    private static final String COMMISSION_ACCOUNT_OWNER = "-2";
    private static final String MARKETING_ACCOUNT_OWNER = "-4";

    private final ErrandRepository errandRepository;
    private final WalletRepository walletRepository;
    private final SnowflakeIdGenerator idGenerator;
    private final CreditRepository creditRepository;
    private final VoucherRepository voucherRepository;
    private final double commissionRate;

    public ArbitrateSettleStep(ErrandRepository errandRepository,
                              WalletRepository walletRepository,
                              SnowflakeIdGenerator idGenerator,
                              CreditRepository creditRepository,
                              VoucherRepository voucherRepository,
                              @Value("${dash.settle.commission-rate:0.08}") double commissionRate) {
        this.errandRepository = errandRepository;
        this.walletRepository = walletRepository;
        this.idGenerator = idGenerator;
        this.creditRepository = creditRepository;
        this.voucherRepository = voucherRepository;
        this.commissionRate = commissionRate;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean settleFromDispute(long errandId, Errand errand, EscrowOrder escrow,
                                     String bizNo, String operatorId) {
        // 闸门 1：托管单 HELD -> RELEASED
        if (walletRepository.casEscrowStatus(errand.campusId(), errandId,
                EscrowOrder.EscrowStatus.HELD, EscrowOrder.EscrowStatus.RELEASED) == 0) {
            log.info("仲裁结算幂等：escrow 已非 HELD errandId={}", errandId);
            return rollbackConflict();
        }
        // 闸门 2：任务 DISPUTED -> SETTLED
        if (errandRepository.casSettleFromDispute(errandId, errand.version()) == 0) {
            log.info("仲裁结算幂等：errand 已非 DISPUTED errandId={}", errandId);
            return rollbackConflict();
        }
        errandRepository.appendStatusLog(errandId, ErrandStatus.DISPUTED, ErrandStatus.SETTLED,
                errand.round(), operatorId);

        // 与正常结算同一套拆账：托管里是实付 P，券面 D 由锁定记录还原
        VoucherUse voucher = voucherRepository.findUseByErrand(errandId).orElse(null);
        long paidCents = escrow.amount().cents();
        long discountCents = voucher == null ? 0L : voucher.discountCents();
        long runnerBearCents = voucher == null ? 0L : voucher.runnerBearCents();
        long rewardCents = paidCents + discountCents;
        long commissionCents = (long) Math.floor(rewardCents * commissionRate);
        long runnerCents = rewardCents - commissionCents - runnerBearCents;
        long subsidyCents = discountCents - runnerBearCents;
        if (runnerCents < 0) {
            throw new BizException(ErrorCode.INVALID_AMOUNT, "优惠券分摊后跑腿实收为负");
        }
        Money runnerAmount = Money.ofCents(runnerCents);
        Money commissionAmount = Money.ofCents(commissionCents);
        Money subsidyAmount = Money.ofCents(subsidyCents);

        WalletAccount escrowAccount = walletRepository
                .findByOwner(ESCROW_ACCOUNT_OWNER, AccountType.ESCROW).orElseThrow();
        WalletAccount runnerAccount = walletRepository
                .findByOwner(errand.grabberId(), AccountType.USER).orElseThrow();
        WalletAccount commissionAccount = walletRepository
                .findByOwner(COMMISSION_ACCOUNT_OWNER, AccountType.COMMISSION).orElseThrow();
        WalletAccount marketingAccount = subsidyCents > 0
                ? walletRepository.findByOwner(MARKETING_ACCOUNT_OWNER, AccountType.MARKETING).orElseThrow()
                : null;

        if (walletRepository.casDebit(escrowAccount.id(), escrow.amount()) != 1) return rollbackConflict();
        if (walletRepository.casCredit(runnerAccount.id(), runnerAmount) != 1) return rollbackConflict();
        if (commissionCents > 0
                && walletRepository.casCredit(commissionAccount.id(), commissionAmount) != 1) return rollbackConflict();
        if (marketingAccount != null
                && walletRepository.casDebit(marketingAccount.id(), subsidyAmount) != 1) return rollbackConflict();

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
        if (marketingAccount != null) {
            walletRepository.insertLedger(new LedgerEntry(
                    idGenerator.nextId(), bizNo, marketingAccount.id(), MARKETING_ACCOUNT_OWNER,
                    LedgerEntry.Direction.DEBIT, subsidyAmount,
                    marketingAccount.available().minus(subsidyAmount),
                    LedgerEntry.RefType.SETTLE, errandId));
        }
        creditRepository.applyEvent(new CreditEvent(
                idGenerator.nextId(), CreditEvent.settleBizNo(errandId), errand.grabberId(),
                CreditEventType.SETTLE, CreditEventType.SETTLE.delta(), "ERRAND", errandId,
                java.time.Instant.now()));
        creditRepository.applyEvent(new CreditEvent(
                idGenerator.nextId(), CreditEvent.settlePublisherBizNo(errandId), errand.publisherId(),
                CreditEventType.SETTLE, CreditEventType.SETTLE.delta(), "ERRAND", errandId,
                java.time.Instant.now()));
        voucherRepository.markUsedByErrand(errandId);
        return true;
    }

    private boolean rollbackConflict() {
        // 方法由独立 Step 的 Spring 代理调用；正常返回冲突也必须撤销整笔事务。
        TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        return false;
    }
}
