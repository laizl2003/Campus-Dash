package com.campusdash.domain.wallet.model;

import com.campusdash.shared.BizException;
import com.campusdash.shared.ErrorCode;
import com.campusdash.shared.Money;

/**
 * 钱包账户：余额快照。
 *
 * 为什么既要快照又要流水：只有流水的话查余额要 SUM 全表，越来越慢；
 * 只有快照的话余额错了无法追溯。所以两者都要，并用对账 job 校验
 * "快照 == 流水累计"。面试问"余额是算出来的还是存出来的"，
 * 标准答案就是：存快照、以流水为准、用对账兜底。
 *
 * ── 关于"托管"该用冻结还是转移（P3 修正的模型缺陷）──
 * P1 的实现同时做了两件事：发单人 available→frozen（冻结），托管账户 available+=（转移）。
 * 结果同一笔钱被记了两次，全局 SUM(available+frozen) 会凭空增加，
 * 而且发单人账户总额不变却记了一笔 DEBIT 流水，"快照 == 流水净额"直接不成立。
 *
 * 正确的区分是：
 *   - 托管（担保交易）= 转移。钱真的离开买家账户进入平台中间户，买家账户里就没这笔钱了。
 *   - 冻结（预授权）= 押金场景。钱不离开账户，只是不可动用。
 * 本项目做的是托管，所以一律用转移模型。frozen 字段保留给未来的提现锁定用。
 */
public class WalletAccount {

    private final long id;
    private final long ownerId;
    private final AccountType type;
    private Money available;
    private Money frozen;
    private long version;

    public WalletAccount(long id, long ownerId, AccountType type, Money available, Money frozen, long version) {
        this.id = id;
        this.ownerId = ownerId;
        this.type = type;
        this.available = available;
        this.frozen = frozen;
        this.version = version;
    }

    /**
     * 资金转出：available 减少，账户总额随之减少（与 DEBIT 流水一一对应）。
     * 领域层先校验一次尽早失败，真正的并发安全靠 SQL 的
     * "UPDATE ... WHERE available >= amount" 带条件 CAS。
     */
    public void transferOut(Money amount) {
        if (available.compareTo(amount) < 0) {
            throw new BizException(ErrorCode.INSUFFICIENT_BALANCE,
                    "available=" + available + " required=" + amount);
        }
        this.available = this.available.minus(amount);
        this.version++;
    }

    /** 资金转入：available 增加（与 CREDIT 流水一一对应） */
    public void transferIn(Money amount) {
        this.available = this.available.plus(amount);
        this.version++;
    }

    /** 账户总额 = 可用 + 冻结，对账时与流水净额比对 */
    public Money total() {
        return available.plus(frozen);
    }

    public long id() { return id; }
    public long ownerId() { return ownerId; }
    public AccountType type() { return type; }
    public Money available() { return available; }
    public Money frozen() { return frozen; }
    public long version() { return version; }
}
