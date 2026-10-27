package com.campusdash.domain.voucher.model;

/** 优惠券状态，落库用小写：available / used / expired */
public enum VoucherStatus {
    AVAILABLE,
    USED,
    EXPIRED;

    public String dbValue() {
        return name().toLowerCase();
    }
}
