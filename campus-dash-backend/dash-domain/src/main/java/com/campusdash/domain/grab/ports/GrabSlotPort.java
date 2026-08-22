package com.campusdash.domain.grab.ports;

import com.campusdash.domain.grab.model.SlotOutcome;

/**
 * 抢单名额端口：由 Redis Lua 脚本实现原子判定。
 *
 * 为什么要有这一层抽象：领域层只关心"抢名额"这个语义，
 * 不关心它是用 Lua、用数据库悲观锁还是用别的机制实现的。
 * 教程第 7 章对比六种方案时，只需换这个端口的实现，业务代码一行不改。
 */
public interface GrabSlotPort {

    /**
     * 原子完成：任务可抢校验 + 名额扣减 + 抢中者写入 + 幂等去重。
     * 这四步必须在一个 Redis Lua 脚本里完成，中间不能被其他客户端插入。
     */
    SlotOutcome tryAcquire(long errandId, long runnerId, String requestId);

    /**
     * 回滚名额。当 Lua 占位成功但 DB 落库失败时调用，防止名额永久泄漏。
     * 必须在事务外执行——事务已经回滚了，这是补偿动作。
     */
    void rollback(long errandId, long runnerId, String requestId);

    /** 任务发布时初始化名额，供抢单判定使用 */
    void initSlot(long errandId, int slotTotal, long ttlSeconds);

    /** 读取剩余名额，用于压测阶段校验 Redis 与 DB 是否一致 */
    long remainingSlot(long errandId);
}
