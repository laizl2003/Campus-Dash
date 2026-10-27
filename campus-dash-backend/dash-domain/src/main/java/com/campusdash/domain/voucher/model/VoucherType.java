package com.campusdash.domain.voucher.model;

import java.util.Arrays;
import java.util.Optional;

/**
 * 优惠券类型。
 *
 * type 用整数枚举落库（需求指定），每个值对应一条"满 threshold 减 discount"的规则，
 * 同时携带初始库存：同一满减价位的第一档 50 张，往后每档减半（已按运营要求整体减半）。
 */
public enum VoucherType {

    FULL_10_MINUS_2(1, 1000, 200, 50),
    FULL_10_MINUS_3(2, 1000, 300, 25),
    FULL_10_MINUS_5(3, 1000, 500, 12),

    FULL_15_MINUS_2(4, 1500, 200, 50),
    FULL_15_MINUS_3(5, 1500, 300, 25),
    FULL_15_MINUS_5(6, 1500, 500, 12),
    FULL_15_MINUS_7(7, 1500, 700, 6),

    FULL_20_MINUS_5(8, 2000, 500, 50),
    FULL_20_MINUS_8(9, 2000, 800, 25),

    FULL_25_MINUS_10(10, 2500, 1000, 50),
    FULL_25_MINUS_15(11, 2500, 1500, 25);

    private final int code;
    private final long thresholdCents;
    private final long discountCents;
    private final int initialStock;

    VoucherType(int code, long thresholdCents, long discountCents, int initialStock) {
        this.code = code;
        this.thresholdCents = thresholdCents;
        this.discountCents = discountCents;
        this.initialStock = initialStock;
    }

    public int code() {
        return code;
    }

    public long thresholdCents() {
        return thresholdCents;
    }

    public long discountCents() {
        return discountCents;
    }

    public int initialStock() {
        return initialStock;
    }

    /** 例如 "满10元减2元" */
    public String description() {
        return "满" + thresholdCents / 100 + "元减" + discountCents / 100 + "元";
    }

    public static Optional<VoucherType> of(int code) {
        return Arrays.stream(values()).filter(t -> t.code == code).findFirst();
    }
}
