package com.campusdash.domain.recon.ports;

import java.time.LocalDate;
import java.util.List;

/**
 * 对账仓储。三层校验都是"用 SQL 找出不该存在的数据"，
 * 所以端口直接暴露三个查询，而不是把全部流水捞到内存里算。
 */
public interface ReconRepository {

    /** L1：全局借贷是否平衡。返回 debit - credit，正常必须为 0 */
    long debitMinusCredit();

    /** L2：快照与流水净额不符的账户 */
    List<AccountDiff> findSnapshotDiffs();

    /** L3：托管单与流水不闭环的任务 */
    List<EscrowDiff> findEscrowClosureDiffs();

    /** 记录差异。同一天同一类型同一主体只记一次（唯一索引幂等） */
    void recordDiff(LocalDate date, String checkType, String subject,
                    Long expected, Long actual, String detail);

    int countDiffs(LocalDate date);

    record AccountDiff(long accountId, long ownerId, long snapshotTotal, long ledgerNet) {}

    record EscrowDiff(long errandId, String escrowStatus, String reason) {}
}
