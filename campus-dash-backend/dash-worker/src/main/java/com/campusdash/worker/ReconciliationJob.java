package com.campusdash.worker;

import com.campusdash.domain.recon.ports.ReconRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 三层对账 job：每天跑一次（默认凌晨 2 点），自动检出资金不一致。
 *
 * ── 三层校验的含义 ──
 *   L1 借贷平衡：SUM(DEBIT) == SUM(CREDIT)。这是复式记账的根本不变式。
 *   L2 快照一致：每个系统户 available+frozen == 流水净额。只有 L1 过了但 L2 没过时，
 *       说明"账目对但余额显示错了"，用户会看到错误余额。
 *   L3 托管闭环：每张 RELEASED/REFUNDED 的托管单都有对应的结算/退款流水；
 *       任务已结算/退款但托管单仍 HELD 也是差异。
 *
 * recon_diff 有记录就说明代码有 bug——不是"允许存在的小误差"。
 * 用 uk_date_type_subject 保证同一天重复跑不产生重复记录。
 */
@Component
public class ReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);

    private final ReconRepository reconRepository;

    public ReconciliationJob(ReconRepository reconRepository) {
        this.reconRepository = reconRepository;
    }

    @Scheduled(cron = "${dash.recon.cron:0 0 2 * * ?}")
    public void runDaily() {
        run(LocalDate.now());
    }

    /** 允许手动触发（指定日期），方便集成测试与压测 */
    public int run(LocalDate date) {
        log.info("对账开始 date={}", date);

        // L1
        long delta = reconRepository.debitMinusCredit();
        if (delta != 0) {
            reconRepository.recordDiff(date, "DEBIT_CREDIT", "GLOBAL", 0L, delta,
                    "全局借贷差额=" + delta + "分");
            log.error("L1 失败：全局借贷不平 delta={}", delta);
        }

        // L2
        List<ReconRepository.AccountDiff> snapshotDiffs = reconRepository.findSnapshotDiffs();
        for (var d : snapshotDiffs) {
            reconRepository.recordDiff(date, "SNAPSHOT", String.valueOf(d.accountId()),
                    d.snapshotTotal(), d.ledgerNet(),
                    "owner=" + d.ownerId() + " snapshot=" + d.snapshotTotal() + " ledger=" + d.ledgerNet());
            log.error("L2 失败：account={} snapshot={} ledger={}", d.accountId(), d.snapshotTotal(), d.ledgerNet());
        }

        // L3
        List<ReconRepository.EscrowDiff> escrowDiffs = reconRepository.findEscrowClosureDiffs();
        for (var d : escrowDiffs) {
            reconRepository.recordDiff(date, "ESCROW_CLOSURE", String.valueOf(d.errandId()),
                    null, null, d.reason());
            log.error("L3 失败：errandId={} reason={}", d.errandId(), d.reason());
        }

        int total = (delta != 0 ? 1 : 0) + snapshotDiffs.size() + escrowDiffs.size();
        log.info("对账完成 date={} diffs={}", date, total);
        return total;
    }
}
