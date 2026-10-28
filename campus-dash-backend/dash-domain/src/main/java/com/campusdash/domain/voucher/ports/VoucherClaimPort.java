package com.campusdash.domain.voucher.ports;

import java.time.Instant;

/** 优惠券 Redis 库存预扣减端口（Lua 原子） */
public interface VoucherClaimPort {

    enum ReserveResult { RESERVED, ALREADY_CLAIMED, SOLD_OUT }

    record Reservation(long voucherId, int type, String userId, Instant expireAt, int remaining) {
    }

    ReserveResult reserve(int type, String userId, long voucherId, Instant expireAt);

    /** 回滚预扣：库存 +1、pending -1、释放一人一单标记（幂等） */
    void rollback(int type, String userId, long voucherId);

    /** worker 落库成功：pending -1（幂等） */
    void confirm(int type, long voucherId);

    /** 启动/缓存缺失时用 DB 库存初始化（不覆盖已有 key） */
    void initialize(int type, int dbStock);

    /** 对账：Redis 目标库存 = DB 库存 - 未确认 pending */
    void reconcile(int type, int dbStock);

    /** 管理端改库存后同步 */
    void syncStock(int type, int dbStock);

    /** 券进入 used/expired 终态：释放一人一单标记，允许再次领取 */
    void release(int type, String userId);
}
