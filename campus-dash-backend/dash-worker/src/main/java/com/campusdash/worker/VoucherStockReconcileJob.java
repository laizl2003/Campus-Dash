package com.campusdash.worker;

import com.campusdash.domain.voucher.ports.VoucherClaimPort;
import com.campusdash.domain.voucher.ports.VoucherRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定期把 Redis 库存校准为 DB 库存 - 未确认 pending */
@Component
public class VoucherStockReconcileJob {

    private static final Logger log = LoggerFactory.getLogger(VoucherStockReconcileJob.class);
    private final VoucherRepository vouchers;
    private final VoucherClaimPort cache;

    public VoucherStockReconcileJob(VoucherRepository vouchers, VoucherClaimPort cache) {
        this.vouchers = vouchers;
        this.cache = cache;
    }

    @Scheduled(fixedDelayString = "${dash.voucher.stock-reconcile-ms:60000}")
    public void reconcile() {
        for (var stock : vouchers.findAllStocks()) {
            cache.reconcile(stock.type(), stock.stock());
        }
    }
}
