package com.campusdash.bootstrap;

import com.campusdash.domain.voucher.model.VoucherStock;
import com.campusdash.domain.voucher.model.VoucherType;
import com.campusdash.domain.voucher.ports.VoucherClaimPort;
import com.campusdash.domain.voucher.ports.VoucherRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时校准优惠券描述与缺失的库存行。
 *
 * 为什么不在 SQL 种子里写中文：MySQL 客户端字符集不一致时（latin1 读 utf8 文件）
 * 会把「满10元减2元」写成乱码，而且这种脏数据会一路传到接口和页面。
 * 用 Java 侧的中文常量写库，字符集由 JDBC 保证，永远不会错。
 */
@Component
public class VoucherStockInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(VoucherStockInitializer.class);

    private final VoucherRepository vouchers;
    private final VoucherClaimPort claimCache;

    public VoucherStockInitializer(VoucherRepository vouchers, VoucherClaimPort claimCache) {
        this.vouchers = vouchers;
        this.claimCache = claimCache;
    }

    @Override
    public void run(ApplicationArguments args) {
        int fixed = 0;
        for (VoucherType type : VoucherType.values()) {
            if (vouchers.findStock(type.code()).isEmpty()) {
                vouchers.insertStock(new VoucherStock(type.code(), type.initialStock(), "ONLINE",
                        type.thresholdCents(), type.discountCents(), type.description(), null));
            } else {
                // 只修文案；库存/状态/门槛以数据库为准，避免启动覆盖管理端修改
                vouchers.updateDescription(type.code(), type.description());
            }
            fixed++;
        }
        // Redis 库存仅在 key 不存在时初始化；已有 key 保留（可能包含机器重启前的预扣）
        for (var stock : vouchers.findAllStocks()) {
            claimCache.initialize(stock.type(), stock.stock());
        }
        log.info("优惠券库存描述已校准 {} 种，Redis 库存已检查", fixed);
    }
}
