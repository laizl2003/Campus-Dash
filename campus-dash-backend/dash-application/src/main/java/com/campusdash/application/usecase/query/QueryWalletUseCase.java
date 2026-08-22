package com.campusdash.application.usecase.query;

import com.campusdash.domain.wallet.ports.WalletQueryPort;
import org.springframework.stereotype.Service;

import java.util.List;

/** 钱包查询：余额 + 流水分页 */
@Service
public class QueryWalletUseCase {

    private final WalletQueryPort queryPort;

    public QueryWalletUseCase(WalletQueryPort queryPort) {
        this.queryPort = queryPort;
    }

    public WalletQueryPort.BalanceView balance(long userId) {
        return queryPort.findBalance(userId)
                .orElse(new WalletQueryPort.BalanceView(0, 0));
    }

    public List<WalletQueryPort.LedgerView> ledger(long userId, int page, int size) {
        return queryPort.ledger(userId, Math.max(page, 0), Math.min(Math.max(size, 1), 50));
    }
}
