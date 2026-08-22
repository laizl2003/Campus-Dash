package com.campusdash.infrastructure.persistence;

import com.campusdash.domain.wallet.ports.WalletQueryPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class JdbcWalletQueryAdapter implements WalletQueryPort {

    private final JdbcTemplate jdbc;

    public JdbcWalletQueryAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<BalanceView> findBalance(long ownerId) {
        List<BalanceView> list = jdbc.query("""
                SELECT available, frozen FROM wallet_account
                 WHERE owner_id = ? AND owner_type = 'USER'
                """, (rs, n) -> new BalanceView(rs.getLong("available"), rs.getLong("frozen")), ownerId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public List<LedgerView> ledger(long ownerId, int page, int size) {
        // user_id 是流水归属人（比如托管流水归属发单人），不是账户 owner——
        // 用 account_id 关联会漏掉"发单人名下的托管借"这类流水
        return jdbc.query("""
                SELECT created_at, direction, amount, ref_type, ref_id, biz_no
                  FROM wallet_ledger
                 WHERE user_id = ?
                 ORDER BY created_at DESC, id DESC
                 LIMIT ? OFFSET ?
                """, (rs, n) -> new LedgerView(
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getString("direction"), rs.getLong("amount"),
                        rs.getString("ref_type"), rs.getLong("ref_id"), rs.getString("biz_no")),
                ownerId, size, page * size);
    }
}
