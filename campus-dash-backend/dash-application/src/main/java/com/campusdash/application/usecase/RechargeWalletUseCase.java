package com.campusdash.application.usecase;

import com.campusdash.domain.wallet.model.AccountType;
import com.campusdash.domain.wallet.model.LedgerEntry;
import com.campusdash.domain.wallet.model.WalletAccount;
import com.campusdash.domain.wallet.ports.WalletRepository;
import com.campusdash.shared.BizException;
import com.campusdash.shared.ErrorCode;
import com.campusdash.shared.Money;
import com.campusdash.shared.SnowflakeIdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 余额充值（演示用）。
 *
 * 关键设计：充值必须记**两笔**流水——外部资金池借记、用户账户贷记。
 * 只记用户侧一笔 CREDIT 会让全局 SUM(DEBIT) - SUM(CREDIT) ≠ 0，
 * 直接破坏对账里的 L1 不变式（实测：并发结算用例报 "L1 借贷必须平衡 expected 0 but -5000"）。
 *
 * 外部资金池（owner_id = "-3"）初始 100 万元，代表"平台外部进来的钱"；
 * 它不在 L2 系统户快照校验里（那层只校验 ESCROW/COMMISSION），
 * 因为它的快照天然比流水多出初始余额。
 */
@Service
public class RechargeWalletUseCase {

    /** 单笔上限 1000 元，防止演示时点错数量级 */
    private static final long MAX_CENTS = 100_000L;
    /** 外部资金池账户：owner_id = -3 */
    private static final String EXTERNAL_OWNER = "-3";
    /** 外部资金池初始额度：100 万元 */
    private static final long EXTERNAL_INITIAL_CENTS = 100_000_000L;

    private final WalletRepository walletRepository;
    private final SnowflakeIdGenerator idGenerator;

    public RechargeWalletUseCase(WalletRepository walletRepository, SnowflakeIdGenerator idGenerator) {
        this.walletRepository = walletRepository;
        this.idGenerator = idGenerator;
    }

    public record Result(long availableCents) {
    }

    @Transactional(rollbackFor = Exception.class)
    public Result recharge(String userId, long amountCents) {
        if (amountCents <= 0 || amountCents > MAX_CENTS) {
            throw new BizException(ErrorCode.INVALID_AMOUNT,
                    "单笔充值需在 0.01 - 1000 元之间，实际=" + amountCents + " 分");
        }
        Money amount = Money.ofCents(amountCents);

        WalletAccount target = walletRepository.findByOwner(userId, AccountType.USER)
                .orElseGet(() -> {
                    WalletAccount created = new WalletAccount(
                            idGenerator.nextId(), userId, AccountType.USER,
                            Money.ZERO, Money.ZERO, 0L);
                    walletRepository.insertAccount(created);
                    return created;
                });
        WalletAccount source = walletRepository.findByOwner(EXTERNAL_OWNER, AccountType.EXTERNAL)
                .orElseGet(() -> {
                    WalletAccount created = new WalletAccount(
                            idGenerator.nextId(), EXTERNAL_OWNER, AccountType.EXTERNAL,
                            Money.ofCents(EXTERNAL_INITIAL_CENTS), Money.ZERO, 0L);
                    walletRepository.insertAccount(created);
                    return created;
                });

        // 外部资金池借记（余额不足说明演示额度用完，属于运营问题而不是用户错误）
        if (walletRepository.casDebit(source.id(), amount) != 1) {
            throw new BizException(ErrorCode.INSUFFICIENT_BALANCE, "外部资金池余额不足");
        }
        if (walletRepository.casCredit(target.id(), amount) != 1) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "充值入账失败，accountId=" + target.id());
        }

        String bizNo = "recharge:" + userId + ":" + idGenerator.nextId();
        Money targetAfter = target.available().plus(amount);
        walletRepository.insertLedger(new LedgerEntry(
                idGenerator.nextId(), bizNo, source.id(), EXTERNAL_OWNER,
                LedgerEntry.Direction.DEBIT, amount,
                source.available().minus(amount),
                LedgerEntry.RefType.RECHARGE, target.id()));
        walletRepository.insertLedger(new LedgerEntry(
                idGenerator.nextId(), bizNo, target.id(), userId,
                LedgerEntry.Direction.CREDIT, amount,
                targetAfter,
                LedgerEntry.RefType.RECHARGE, target.id()));

        return new Result(targetAfter.cents());
    }
}
