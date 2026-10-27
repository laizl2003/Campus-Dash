package com.campusdash.domain.voucher.model;

import java.time.Instant;

/** 库存视图：type 唯一，status 表示上下架，threshold/discount 为满减规则（分） */
public record VoucherStock(int type, int stock, String status, long thresholdCents, long discountCents,
                           String description, Instant lastChangeTime) {

    public boolean online() {
        return "ONLINE".equalsIgnoreCase(status);
    }
}
