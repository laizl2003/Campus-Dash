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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class JdbcWalletRepository implements WalletRepository {

    private final JdbcTemplate jdbc;

    public JdbcWalletRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<WalletAccount> MAPPER = (rs, n) -> new WalletAccount(
            rs.getLong("id"),
            rs.getString("owner_id"),
            rs.getString("user_name"),
            AccountType.valueOf(rs.getString("owner_type")),
            Money.ofCents(rs.getLong("available")),
            Money.ofCents(rs.getLong("frozen")),
            rs.getLong("version"));

    @Override
    public Optional<WalletAccount> findByOwner(String ownerId, AccountType type) {
        List<WalletAccount> list = jdbc.query(
                "SELECT * FROM wallet_account WHERE owner_id = ? AND owner_type = ?",
                MAPPER, ownerId, type.name());
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public PlatformLedgerPage queryPlatformLedger(AccountType type, Instant from, int offset, int limit) {
        String timeFilter = from == null ? "" : " AND l.created_at >= ?";
        Object[] base = from == null
                ? new Object[]{type.name()}
                : new Object[]{type.name(), Timestamp.from(from)};

        String select = """
                SELECT l.id, l.biz_no, l.account_id, l.direction, l.amount, l.balance_after,
                       l.ref_type, l.ref_id, l.created_at,
                       a.user_name, a.owner_type
                  FROM wallet_ledger l
                  JOIN wallet_account a ON a.id = l.account_id
                 WHERE a.owner_type = ?""" + timeFilter;
        List<PlatformLedgerRow> items = jdbc.query(select + " ORDER BY l.id DESC LIMIT ? OFFSET ?",
                (rs, n) -> new PlatformLedgerRow(
                        rs.getLong("id"), rs.getString("biz_no"), rs.getLong("account_id"),
                        rs.getString("user_name"), rs.getString("owner_type"),
                        rs.getString("direction"), rs.getLong("amount"), rs.getLong("balance_after"),
                        rs.getString("ref_type"), rs.getLong("ref_id"),
                        rs.getTimestamp("created_at").toInstant()),
                concat(base, limit, offset));

        Long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM wallet_ledger l
                  JOIN wallet_account a ON a.id = l.account_id
                 WHERE a.owner_type = ?""" + timeFilter, Long.class, base);

        Map<String, Long> sums = jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN l.direction='CREDIT' THEN l.amount ELSE 0 END), 0) AS income,
                       COALESCE(SUM(CASE WHEN l.direction='DEBIT'  THEN l.amount ELSE 0 END), 0) AS expense
                  FROM wallet_ledger l
                  JOIN wallet_account a ON a.id = l.account_id
                 WHERE a.owner_type = ?""" + timeFilter, (rs, n) ->
                        Map.of("income", rs.getLong("income"), "expense", rs.getLong("expense")), base);

        long income = sums == null ? 0 : sums.getOrDefault("income", 0L);
        long expense = sums == null ? 0 : sums.getOrDefault("expense", 0L);
        return new PlatformLedgerPage(items, total == null ? 0 : total, income, expense);
    }

    @Override
    public long netPlatformIncomeCents() {
        Long net = jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN a.owner_type='COMMISSION' AND l.direction='CREDIT' THEN l.amount ELSE 0 END), 0)
                     - COALESCE(SUM(CASE WHEN a.owner_type='MARKETING' AND l.direction='DEBIT'  THEN l.amount ELSE 0 END), 0)
                  FROM wallet_ledger l
                  JOIN wallet_account a ON a.id = l.account_id
                """, Long.class);
        return net == null ? 0 : net;
    }

    private static Object[] concat(Object[] base, Object... tail) {
        Object[] all = new Object[base.length + tail.length];
        System.arraycopy(base, 0, all, 0, base.length);
        System.arraycopy(tail, 0, all, base.length, tail.length);
        return all;
    }

    @Override
    public void deleteAccount(long accountId) {
        jdbc.update("DELETE FROM wallet_account WHERE id = ?", accountId);
    }

    @Override
    public void insertAccount(WalletAccount account) {
        jdbc.update("""
                INSERT INTO wallet_account (id, owner_id, user_name, owner_type, available, frozen, version)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, account.id(), account.ownerId(), account.userName(), account.type().name(),
                account.available().cents(), account.frozen().cents(), account.version());
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
                        rs.getLong("errand_id"), rs.getString("publisher_id"),
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
