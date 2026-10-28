package com.campusdash.worker;

import com.campusdash.domain.voucher.ports.VoucherClaimPort;
import com.campusdash.domain.voucher.ports.VoucherRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 优惠券过期兜底扫描。
 *
 * 主通道是 RocketMQ 延迟消息（VoucherExpireConsumer）：
 * 领取时把"到期作废"写进本地消息表，到点投递，消费者改状态。
 * 但消息可能因为 broker 重启、消息丢失、消费者长时间离线而没有到达，
 * 所以再加一条按 DB 事实扫描的兜底——和超时流转的"主通道 + 扫描"是同一个思路。
 *
 * 幂等：UPDATE 只命中 status='available' 且已过期的行，重复执行不会误伤
 * locked（已锁定到进行中订单）和 used（已使用）的券。
 */
@Component
public class VoucherExpireScanJob {

    private static final Logger log = LoggerFactory.getLogger(VoucherExpireScanJob.class);
    private static final int BATCH_LIMIT = 200;

    private final VoucherRepository vouchers;
    private final VoucherClaimPort claimCache;

    public VoucherExpireScanJob(VoucherRepository vouchers, VoucherClaimPort claimCache) {
        this.vouchers = vouchers;
        this.claimCache = claimCache;
    }

    @Scheduled(fixedDelayString = "${dash.voucher.expire-scan-ms:60000}",
               initialDelayString = "${dash.voucher.expire-scan-initial-ms:15000}")
    public void scan() {
        int expired = 0;
        for (var voucher : vouchers.findExpiredAvailable(BATCH_LIMIT)) {
            if (vouchers.markExpired(voucher.voucherId()) == 1) {
                claimCache.release(voucher.type(), voucher.userId());
                expired++;
            }
        }
        if (expired > 0) {
            log.info("优惠券过期扫描：本次作废 {} 张", expired);
        }
    }
}
