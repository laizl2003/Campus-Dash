package com.campusdash.domain.voucher.model;

/** 优惠券状态，落库用小写：available / locked / used / expired */
public enum VoucherStatus {
    AVAILABLE,
    /** 已随任务发布锁定，等待结算；不是"已使用" */
    LOCKED,
    USED,
    EXPIRED;

    public String dbValue() {
        return name().toLowerCase();
    }
}
