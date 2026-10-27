package com.campusdash.application.usecase;

import com.campusdash.domain.auth.model.UserAccount;
import com.campusdash.domain.auth.ports.UserRepository;
import com.campusdash.domain.errand.ports.ErrandRepository;
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
 * 注销账户。
 *
 * 规则：
 *   1. 管理员账号不允许注销（平台需要保留一个后台入口）；
 *   2. 还有进行中的任务（发单或跑腿）不允许注销，避免把别人的订单挂死；
 *   3. 钱包余额全额转入佣金账户（平台收入），并记一借一贷两条流水，保持 L1 平衡；
 *   4. 删除 user 行与钱包行；wallet_ledger 保留，作为历史事实。
 */
@Service
public class DeleteAccountUseCase {

    private static final String COMMISSION_OWNER = "-2";

    private final UserRepository users;
    private final WalletRepository wallets;
    private final ErrandRepository errands;
    private final SnowflakeIdGenerator idGenerator;

    public DeleteAccountUseCase(UserRepository users, WalletRepository wallets,
                                ErrandRepository errands, SnowflakeIdGenerator idGenerator) {
        this.users = users;
        this.wallets = wallets;
        this.errands = errands;
        this.idGenerator = idGenerator;
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(String userId) {
        UserAccount user = users.findById(userId)
                .orElseThrow(() -> new BizException(ErrorCode.UNAUTHORIZED, "账号不存在"));

        if (user.isAdmin()) {
            throw new BizException(ErrorCode.ACCOUNT_DELETE_FORBIDDEN);
        }
        if (errands.hasOngoing(userId)) {
            throw new BizException(ErrorCode.ACCOUNT_HAS_ONGOING);
        }

        wallets.findByOwner(userId, AccountType.USER).ifPresent(wallet -> {
            long cents = wallet.available().cents();
            if (cents > 0) {
                Money amount = Money.ofCents(cents);
                WalletAccount commission = wallets.findByOwner(COMMISSION_OWNER, AccountType.COMMISSION)
                        .orElseThrow(() -> new BizException(ErrorCode.ACCOUNT_NOT_FOUND, "佣金账户缺失"));
                String bizNo = "close:" + userId;

                if (wallets.casDebit(wallet.id(), amount) != 1) {
                    throw new BizException(ErrorCode.INSUFFICIENT_BALANCE, "余额转出失败");
                }
                if (wallets.casCredit(commission.id(), amount) != 1) {
                    throw new BizException(ErrorCode.ACCOUNT_NOT_FOUND, "佣金账户入账失败");
                }
                // 注销转出：用户侧 DEBIT、佣金户 CREDIT，金额相等
                wallets.insertLedger(new LedgerEntry(
                        idGenerator.nextId(), bizNo, wallet.id(), userId,
                        LedgerEntry.Direction.DEBIT, amount, Money.ZERO,
                        LedgerEntry.RefType.CLOSE, 0));
                wallets.insertLedger(new LedgerEntry(
                        idGenerator.nextId(), bizNo, commission.id(), COMMISSION_OWNER,
                        LedgerEntry.Direction.CREDIT, amount, commission.available().plus(amount),
                        LedgerEntry.RefType.CLOSE, 0));
            }
            wallets.deleteAccount(wallet.id());
        });

        if (!users.deleteById(userId)) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "账号删除失败");
        }
    }
}
