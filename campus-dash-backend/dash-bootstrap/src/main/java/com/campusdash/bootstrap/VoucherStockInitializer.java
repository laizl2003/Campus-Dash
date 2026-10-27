package com.campusdash.bootstrap;

import com.campusdash.domain.voucher.model.VoucherType;
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

    public VoucherStockInitializer(VoucherRepository vouchers) {
        this.vouchers = vouchers;
    }

    @Override
    public void run(ApplicationArguments args) {
        int fixed = 0;
        for (VoucherType type : VoucherType.values()) {
            int updated = vouchers.updateDescription(type.code(), type.description());
            if (updated == 0) {
                vouchers.insertStockIfAbsent(type.code(), type.initialStock(), type.description());
                fixed++;
                continue;
            }
            fixed++;
        }
        log.info("优惠券库存描述已校准 {} 种", fixed);
    }
}
