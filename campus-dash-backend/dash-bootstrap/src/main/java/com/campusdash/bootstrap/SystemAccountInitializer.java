package com.campusdash.bootstrap;

import com.campusdash.domain.wallet.model.AccountType;
import com.campusdash.domain.wallet.model.WalletAccount;
import com.campusdash.domain.wallet.ports.WalletRepository;
import com.campusdash.shared.Money;
import com.campusdash.shared.SnowflakeIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 系统账户：托管户 -1、佣金户 -2、外部资金池 -3、营销户 -4。
 *
 * 优惠券补贴从营销户出，所以它必须预先有余额（演示给 100 万元）。
 * 真实系统里营销预算是财务打进来的，这里用初始化器等价表达。
 */
@Component
public class SystemAccountInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SystemAccountInitializer.class);

    private final WalletRepository wallets;
    private final SnowflakeIdGenerator idGenerator;

    public SystemAccountInitializer(WalletRepository wallets, SnowflakeIdGenerator idGenerator) {
        this.wallets = wallets;
        this.idGenerator = idGenerator;
    }

    @Override
    public void run(ApplicationArguments args) {
        ensure("-1", "Admin", AccountType.ESCROW, 0L);
        ensure("-2", "Admin", AccountType.COMMISSION, 0L);
        ensure("-3", "Admin", AccountType.EXTERNAL, 100_000_000L);
        ensure("-4", "Admin", AccountType.MARKETING, 100_000_000L);
    }

    private void ensure(String ownerId, String userName, AccountType type, long initialCents) {
        if (wallets.findByOwner(ownerId, type).isPresent()) {
            return;
        }
        wallets.insertAccount(new WalletAccount(idGenerator.nextId(), ownerId, userName, type,
                Money.ofCents(initialCents), Money.ZERO, 0L));
        log.info("已创建系统账户 owner={} type={} initial={}", ownerId, type, initialCents);
    }
}
