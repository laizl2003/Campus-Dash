package com.campusdash.domain.voucher.model;

import java.time.Instant;

/**
 * 一张已领取的优惠券。
 * voucherId 用雪花 ID：高并发领取时由应用侧生成，避免数据库自增成为热点。
 */
public record Voucher(long voucherId, String userId, int type, Instant expireTime,
                      VoucherStatus status, Instant finishAt, Instant createdAt) {
}
