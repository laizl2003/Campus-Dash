package com.campusdash.infrastructure.persistence;

import com.campusdash.domain.wallet.model.AccountType;
import com.campusdash.domain.wallet.model.EscrowOrder;
import com.campusdash.domain.wallet.model.LedgerEntry;
import com.campusdash.domain.wallet.model.WalletAccount;
import com.campusdash.domain.wallet.ports.WalletRepository;
import com.campusdash.shared.Money;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class JdbcWalletRepository implements WalletRepository {

    private final JdbcTemplate jdbc;

    public JdbcWalletRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<WalletAccount> MAPPER = (rs, n) -> new WalletAccount(
            rs.getLong("id"),
            rs.getLong("owner_id"),
            AccountType.valueOf(rs.getString("owner_type")),
            Money.ofCents(rs.getLong("available")),
            Money.ofCents(rs.getLong("frozen")),
            rs.getLong("version"));

    @Override
    public Optional<WalletAccount> findByOwner(long ownerId, AccountType type) {
        List<WalletAccount> list = jdbc.query(
                "SELECT * FROM wallet_account WHERE owner_id = ? AND owner_type = ?",
                MAPPER, ownerId, type.name());
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /**
     * 带条件的 CAS 转出，一条语句同时完成"判断余额是否够"和"扣减"。
     *
     * P3 修正：原实现是 available -= amount 同时 frozen += amount（预授权语义），
     * 与"资金真实转入托管账户"叠加后，同一笔钱在快照里被算了两次，
     * 全局 SUM(available+frozen) 凭空增加，且账户总额不变却记了 DEBIT 流水。
     * 托管是资金转移，所以这里只减 available，与 DEBIT 流水一一对应。
     *
     * 为什么不能先 SELECT 再 UPDATE：两条语句之间余额可能被其他事务改掉，
     * 那是典型的检查-使用竞态。把条件写进 WHERE 让数据库在一个原子动作里判定。
     */
    @Override
    public int casDebit(long accountId, Money amount) {
        return jdbc.update("""
                UPDATE wallet_account
                   SET available = available - ?,
                       version = version + 1
                 WHERE id = ? AND available >= ?
                """, amount.cents(), accountId, amount.cents());
    }

    @Override
    public int casCredit(long accountId, Money amount) {
        return jdbc.update("""
                UPDATE wallet_account
                   SET available = available + ?, version = version + 1
                 WHERE id = ?
                """, amount.cents(), accountId);
    }

    @Override
    public void insertLedger(LedgerEntry entry) {
        jdbc.update("""
                INSERT INTO wallet_ledger (id, biz_no, account_id, user_id, direction,
                                           amount, balance_after, ref_type, ref_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                entry.id(), entry.bizNo(), entry.accountId(), entry.userId(),
                entry.direction().name(), entry.amount().cents(), entry.balanceAfter().cents(),
                entry.refType().name(), entry.refId());
    }

    @Override
    public void insertEscrow(EscrowOrder order) {
        jdbc.update("""
                INSERT INTO escrow_order (id, campus_id, errand_id, publisher_id, amount, status)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                order.id(), order.campusId(), order.errandId(), order.publisherId(),
                order.amount().cents(), order.status().name());
    }

    @Override
    public boolean escrowExists(long campusId, long errandId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM escrow_order WHERE campus_id = ? AND errand_id = ?",
                Integer.class, campusId, errandId);
        return n != null && n > 0;
    }

    @Override
    public Optional<WalletAccount> findById(long accountId) {
        List<WalletAccount> list = jdbc.query(
                "SELECT * FROM wallet_account WHERE id = ?", MAPPER, accountId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public Optional<EscrowOrder> findEscrowByErrandId(long campusId, long errandId) {
        List<EscrowOrder> list = jdbc.query("""
                SELECT id, campus_id, errand_id, publisher_id, amount, status
                  FROM escrow_order WHERE campus_id = ? AND errand_id = ?
                """, (rs, n) -> new EscrowOrder(
                        rs.getLong("id"), rs.getLong("campus_id"),
                        rs.getLong("errand_id"), rs.getLong("publisher_id"),
                        Money.ofCents(rs.getLong("amount")),
                        EscrowOrder.EscrowStatus.valueOf(rs.getString("status"))), campusId, errandId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /**
     * 托管单状态 CAS：并发重复结算的第一道闸门。
     * 两个线程同时结算同一任务，只有一个能把 HELD 改成 RELEASED，
     * 另一个 affectedRows=0，直接判定为重复请求。
     */
    @Override
    public int casEscrowStatus(long campusId, long errandId, EscrowOrder.EscrowStatus from, EscrowOrder.EscrowStatus to) {
        return jdbc.update("""
                UPDATE escrow_order SET status = ? WHERE campus_id = ? AND errand_id = ? AND status = ?
                """, to.name(), campusId, errandId, from.name());
    }

    @Override
    public boolean ledgerExists(String bizNo) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM wallet_ledger WHERE biz_no = ?", Integer.class, bizNo);
        return n != null && n > 0;
    }
}
