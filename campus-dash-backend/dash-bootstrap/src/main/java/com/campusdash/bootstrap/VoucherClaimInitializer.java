package com.campusdash.bootstrap;

import com.campusdash.domain.voucher.model.Voucher;
import com.campusdash.domain.voucher.ports.VoucherClaimPort;
import com.campusdash.domain.voucher.ports.VoucherRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Redis 重启后重建优惠券“一人一单”标记，避免已有券的用户再次领取。 */
@Component
public class VoucherClaimInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(VoucherClaimInitializer.class);

    private final VoucherRepository vouchers;
    private final VoucherClaimPort claimCache;

    public VoucherClaimInitializer(VoucherRepository vouchers, VoucherClaimPort claimCache) {
        this.vouchers = vouchers;
        this.claimCache = claimCache;
    }

    @Override
    public void run(ApplicationArguments args) {
        int restored = 0;
        for (Voucher voucher : vouchers.findActiveClaims()) {
            claimCache.seedClaimed(voucher.type(), voucher.userId(), voucher.expireTime());
            restored++;
        }
        if (restored > 0) {
            log.info("优惠券已领取标记已从 DB 重建 {} 个", restored);
        }
    }
}
