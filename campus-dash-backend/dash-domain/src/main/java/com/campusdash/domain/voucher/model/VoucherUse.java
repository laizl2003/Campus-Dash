package com.campusdash.domain.voucher.model;

/** 某订单锁定的券：结算时用它算跑腿实收与平台补贴 */
public record VoucherUse(long voucherId, int type, long discountCents, long runnerBearCents) {
}
