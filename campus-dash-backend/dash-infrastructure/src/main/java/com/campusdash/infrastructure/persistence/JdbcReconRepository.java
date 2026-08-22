package com.campusdash.infrastructure.persistence;

import com.campusdash.domain.recon.ports.ReconRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * 三层对账的 JDBC 实现。
 *
 * 三层校验全部用 SQL 完成而不是把流水捞到内存里算：
 * 流水表是只增不减的，量级会一直涨，捞到内存迟早 OOM。
 * 用 SQL 的另一个好处是"对账逻辑可以直接贴给 DBA 复核"。
 */
@Repository
public class JdbcReconRepository implements ReconRepository {

    private final JdbcTemplate jdbc;

    public JdbcReconRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** L1：复式记账的根本不变式。每笔资金动作一借一贷金额相等，全局必然平衡 */
    @Override
    public long debitMinusCredit() {
        Long v = jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN direction = 'DEBIT'  THEN amount ELSE 0 END), 0)
                     - COALESCE(SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE 0 END), 0)
                  FROM wallet_ledger
                """, Long.class);
        return v == null ? 0L : v;
    }

    /**
     * L2：快照 == 流水净额。
     *
     * 这一层能抓到最隐蔽的 bug：流水写对了但快照 UPDATE 漏了（或反过来）。
     * 只有 L1 通过而 L2 失败时，说明"账目对但余额错"，用户会看到错误余额。
     *
     * 注意 USER 账户的初始余额是 seed 直接给的、没有对应流水（等价于线下充值），
     * 所以口径是"快照总额 - 初始余额 == 流水净额"。本项目 seed 固定给 100000/5000，
     * 校验时用 wallet_account.created_at 之后的流水做增量比对会更准，
     * 这里选择更朴素的方式：只校验系统户（ESCROW/COMMISSION，初始为 0），
     * 用户账户的校验放在 S4 压测里用"压测前后差额"来断言。
     */
    @Override
    public List<AccountDiff> findSnapshotDiffs() {
        return jdbc.query("""
                SELECT a.id, a.owner_id, (a.available + a.frozen) AS snapshot_total,
                       COALESCE((SELECT SUM(CASE WHEN l.direction = 'CREDIT' THEN l.amount
                                                 ELSE -l.amount END)
                                   FROM wallet_ledger l WHERE l.account_id = a.id), 0) AS ledger_net
                  FROM wallet_account a
                 WHERE a.owner_type IN ('ESCROW', 'COMMISSION')
                HAVING snapshot_total <> ledger_net
                """, (rs, n) -> new AccountDiff(
                        rs.getLong("id"), rs.getLong("owner_id"),
                        rs.getLong("snapshot_total"), rs.getLong("ledger_net")));
    }

    /**
     * L3：托管闭环。
     * 三种不该存在的情况：
     *   1. RELEASED 但没有 settle 流水 —— 钱标记为已结算却没真的转出去
     *   2. REFUNDED 但没有 refund 流水 —— 同理
     *   3. 任务已 SETTLED/REFUNDED 但托管单还是 HELD —— 状态机与资金脱节
     */
    @Override
    public List<EscrowDiff> findEscrowClosureDiffs() {
        return jdbc.query("""
                SELECT e.errand_id, e.status AS escrow_status, 'RELEASED 缺少结算流水' AS reason
                  FROM escrow_order e
                 WHERE e.status = 'RELEASED'
                   AND NOT EXISTS (SELECT 1 FROM wallet_ledger l
                                    WHERE l.biz_no = CONCAT('settle:', e.errand_id))
                UNION ALL
                SELECT e.errand_id, e.status, 'REFUNDED 缺少退款流水'
                  FROM escrow_order e
                 WHERE e.status = 'REFUNDED'
                   AND NOT EXISTS (SELECT 1 FROM wallet_ledger l
                                    WHERE l.biz_no = CONCAT('refund:', e.errand_id))
                UNION ALL
                SELECT e.errand_id, e.status, CONCAT('任务已', r.status, ' 但托管单仍 HELD')
                  FROM escrow_order e
                  JOIN errand r ON r.id = e.errand_id
                 WHERE e.status = 'HELD' AND r.status IN ('SETTLED', 'REFUNDED', 'CANCELLED')
                """, (rs, n) -> new EscrowDiff(
                        rs.getLong("errand_id"), rs.getString(2), rs.getString(3)));
    }

    @Override
    public void recordDiff(LocalDate date, String checkType, String subject,
                           Long expected, Long actual, String detail) {
        try {
            jdbc.update("""
                    INSERT INTO recon_diff (check_date, check_type, subject, expected, actual, detail)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, date, checkType, subject, expected, actual, detail);
        } catch (DuplicateKeyException e) {
            // 同一天同一主体的同类差异已记过，重复跑对账不产生重复记录
        }
    }

    @Override
    public int countDiffs(LocalDate date) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM recon_diff WHERE check_date = ?", Integer.class, date);
        return n == null ? 0 : n;
    }
}
