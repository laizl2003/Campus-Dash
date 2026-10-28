package com.campusdash.domain.voucher.ports;

/** 领取优惠券 MQ 事件发布端口 */
public interface VoucherClaimPublisher {
    void publish(long voucherId, int type, String userId, String expireAt, String description);
}
