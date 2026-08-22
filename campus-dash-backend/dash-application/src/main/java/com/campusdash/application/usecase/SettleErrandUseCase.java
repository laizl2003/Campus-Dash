package com.campusdash.application.usecase;

import com.campusdash.domain.credit.model.CreditEvent;
import com.campusdash.domain.credit.model.CreditEventType;
import com.campusdash.domain.credit.ports.CreditRankingPort;
import com.campusdash.domain.credit.ports.CreditRepository;
import com.campusdash.domain.notify.ports.RealtimeNotifier;
import com.campusdash.domain.errand.model.Errand;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 结算用例：DELIVERED -> SETTLED，资金从托管账户分配给跑腿与佣金账户。
 *
 * ── 并发幂等三道闸门 ──
 *   1. escrow_order 状态 CAS：HELD -> RELEASED，只有一个线程能改成功
 *   2. errand CAS：DELIVERED -> SETTLED，只有一个线程能改成功
 *   3. wallet_ledger 唯一索引：biz_no='settle:{id}' 重复插入撞索引
 * 三道独立的闸门确保"即使前面的被绕过（理论上不可能），后面的也会兜住"。
 *
 * ── 佣金计算 ──
 * 佣金 = 托管金额 × commissionRate，向下取整到分。
 * 跑腿所得 = 托管金额 - 佣金。
 * 这样"跑腿 + 佣金 == 托管"恒成立，不会因四舍五入丢 1 分。
 */
@Service
public class SettleErrandUseCase {

    private static final Logger log = LoggerFactory.getLogger(SettleErrandUseCase.class);
    private static final long ESCROW_ACCOUNT_OWNER = -1L;
    private static final long COMMISSION_ACCOUNT_OWNER = -2L;

    private final ErrandRepository errandRepository;
    private final WalletRepository walletRepository;
    private final FundAuditPort auditPort;
    private final FundEventPort fundEventPort;
    private final SnowflakeIdGenerator idGenerator;
    private final double commissionRate;
    private final CacheEvictSupport cacheEvict;
    private final CreditRepository creditRepository;
    private final CreditRankingPort creditRankingPort;
    private final TransactionTemplate transactionTemplate;
    private final RealtimeNotifier notifier;

    public SettleErrandUseCase(ErrandRepository errandRepository,
                               WalletRepository walletRepository,
                               FundAuditPort auditPort,
                               FundEventPort fundEventPort,
                               SnowflakeIdGenerator idGenerator,
                               CacheEvictSupport cacheEvict,
                               CreditRepository creditRepository,
                               CreditRankingPort creditRankingPort,
                               TransactionTemplate transactionTemplate,
                               RealtimeNotifier notifier,
                               @Value("${dash.settle.commission-rate:0.05}") double commissionRate) {
        this.errandRepository = errandRepository;
        this.walletRepository = walletRepository;
        this.auditPort = auditPort;
        this.fundEventPort = fundEventPort;
        this.idGenerator = idGenerator;
        this.cacheEvict = cacheEvict;
        this.creditRepository = creditRepository;
        this.creditRankingPort = creditRankingPort;
        this.transactionTemplate = transactionTemplate;
        this.notifier = notifier;
        this.commissionRate = commissionRate;
    }

    public enum Result { SETTLED, ALREADY_SETTLED, CONFLICT }

    /**
     * @param operatorId 发单人确认时传发单人 ID；自动结算时传 Errand.SYSTEM_OPERATOR
     */
    public Result settle(long errandId, long operatorId) {
        Errand errand = errandRepository.findById(errandId)
                .orElseThrow(() -> new BizException(ErrorCode.ERRAND_NOT_FOUND, "id=" + errandId));

        if (errand.status() == com.campusdash.domain.errand.model.ErrandStatus.SETTLED) {
            return Result.ALREADY_SETTLED;
        }

        EscrowOrder escrow = walletRepository.findEscrowByErrandId(errand.campusId(), errandId)
                .orElseThrow(() -> new BizException(ErrorCode.ESCROW_NOT_FOUND, "errandId=" + errandId));

        // 计算佣金（向下取整，余数归跑腿）
        long totalCents = escrow.amount().cents();
        long commissionCents = (long) Math.floor(totalCents * commissionRate);
        long runnerCents = totalCents - commissionCents;
        Money runnerAmount = Money.ofCents(runnerCents);
        Money commissionAmount = Money.ofCents(commissionCents);

        String bizNo = LedgerEntry.settleBizNo(errandId);
        FundEvent event = new FundEvent(bizNo, "SETTLED", errandId, errand.publisherId(),
                errand.grabberId() != null ? errand.grabberId() : 0L, runnerCents, commissionCents);

        // 注意：lambda 里的 this 是原始对象不是 Spring 代理，方法上的 @Transactional
        // 在自调用下不生效（P3 遗留隐患，P5 修复）。改用程序化事务，
        // 保证"资金分账 + 信用事件"要么一起提交、要么一起回滚
        boolean committed = fundEventPort.publishInTransaction(event, () ->
                Boolean.TRUE.equals(transactionTemplate.execute(status ->
                        doSettle(errandId, errand, escrow, runnerAmount, commissionAmount, bizNo, operatorId))));

        if (committed) {
            // 状态变为 SETTLED，详情缓存失效。这里事务已提交（publishInTransaction 返回后），
            // 所以 evictAfterCommit 会走"无事务上下文"分支立即删除
            cacheEvict.evictAfterCommit(errandId);
            // 排行榜是展示层：事务提交后更新，Redis 失败由每日校准 job 自愈
            if (errand.grabberId() != null) {
                creditRankingPort.update(errand.campusId(), errand.grabberId(),
                        creditRepository.scoreOf(errand.grabberId()));
            }
            auditPort.record(bizNo, "SETTLE", errandId, operatorId,
                    String.format("{\"runner\":%d,\"commission\":%d}", runnerCents, commissionCents),
                    true, null);
            // 实时推送：状态变更 + 跑腿信用分变化
            notifier.errandStatusChanged(errandId, errand.publisherId(), errand.grabberId(),
                    com.campusdash.domain.errand.model.ErrandStatus.SETTLED.name(), errand.round());
            if (errand.grabberId() != null) {
                notifier.creditChanged(errand.grabberId(),
                        creditRepository.scoreOf(errand.grabberId()),
                        com.campusdash.domain.credit.model.CreditEventType.SETTLE.delta(),
                        "完成结算");
            }
            return Result.SETTLED;
        }
        return Result.CONFLICT;
    }

    /**
     * 结算的事务体。由 TransactionTemplate 包裹执行（见 settle 里的注释），
     * 不依赖 @Transactional 代理——lambda 自调用场景下代理不可靠。
     */
    boolean doSettle(long errandId, Errand errand, EscrowOrder escrow,
                     Money runnerAmount, Money commissionAmount, String bizNo, long operatorId) {
        // 闸门 1：托管单状态 CAS
        int escrowUpdated = walletRepository.casEscrowStatus(errand.campusId(), errandId,
                EscrowOrder.EscrowStatus.HELD, EscrowOrder.EscrowStatus.RELEASED);
        if (escrowUpdated == 0) {
            log.info("结算幂等：escrow 已非 HELD errandId={}", errandId);
            return false;
        }

        // 闸门 2：任务状态 CAS
        int errandUpdated = errandRepository.casSettle(errandId, errand.version());
        if (errandUpdated == 0) {
            log.info("结算幂等：errand 已非 DELIVERED errandId={}", errandId);
            return false;
        }
        errandRepository.appendStatusLog(errandId,
                com.campusdash.domain.errand.model.ErrandStatus.DELIVERED,
                com.campusdash.domain.errand.model.ErrandStatus.SETTLED,
                errand.round(), operatorId);

        // 资金转移：托管账户 -> 跑腿 + 佣金
        WalletAccount escrowAccount = walletRepository.findByOwner(ESCROW_ACCOUNT_OWNER, AccountType.ESCROW)
                .orElseThrow();
        WalletAccount runnerAccount = walletRepository.findByOwner(errand.grabberId(), AccountType.USER)
                .orElseThrow();
        WalletAccount commissionAccount = walletRepository.findByOwner(COMMISSION_ACCOUNT_OWNER, AccountType.COMMISSION)
                .orElseThrow();

        // 托管户扣减
        walletRepository.casDebit(escrowAccount.id(), escrow.amount());
        // 跑腿加
        walletRepository.casCredit(runnerAccount.id(), runnerAmount);
        // 佣金加
        walletRepository.casCredit(commissionAccount.id(), commissionAmount);

        // 流水（闸门 3：bizNo 唯一索引）
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
        if (commissionAmount.cents() > 0) {
            walletRepository.insertLedger(new LedgerEntry(
                    idGenerator.nextId(), bizNo, commissionAccount.id(), COMMISSION_ACCOUNT_OWNER,
                    LedgerEntry.Direction.CREDIT, commissionAmount,
                    commissionAccount.available().plus(commissionAmount),
                    LedgerEntry.RefType.SETTLE, errandId));
        }

        // 信用事件与资金动作同事务：结算成功则跑腿 +2 分，无中间态（P5 验收标准第 5 条）。
        // biz_no 唯一索引保证重复结算不重复计分
        creditRepository.applyEvent(new CreditEvent(
                idGenerator.nextId(), CreditEvent.settleBizNo(errandId), errand.grabberId(),
                CreditEventType.SETTLE, CreditEventType.SETTLE.delta(), "ERRAND", errandId,
                java.time.Instant.now()));
        return true;
    }
}
