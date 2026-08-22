package com.campusdash.domain.errand.ports;

import java.time.Instant;
import java.util.List;

/**
 * 本地消息表端口——跨进程最终一致的主方案。
 *
 * 为什么选它而不是 RocketMQ 事务消息：它把"消息有没有发出去"变成了一个
 * 可查询的数据库状态，运维和排障成本最低。事务消息的回查逻辑本质上
 * 还是要查"业务数据到底写成功没有"，复杂度只是转移了，没有消失。
 */
public interface LocalMessageRepository {

    /**
     * 在业务事务内登记待发消息。
     * msg_key 唯一索引保证同一轮只登记一次——幂等三件套的第三件。
     *
     * @return true 登记成功，false 该 msgKey 已存在（重复请求）
     */
    boolean enqueue(long id, String msgKey, String topic, String payload, Instant deliverAt);

    /** 发送成功后标记 SENT */
    void markSent(String msgKey);

    /** 扫描待重发消息（发送失败或应用崩溃遗留的 PENDING） */
    List<PendingMessage> findPending(int limit);

    /** 重试失败：累加次数并按指数退避推迟下次重试；超过上限转 DEAD */
    void markRetry(String msgKey, int maxRetry);

    record PendingMessage(long id, String msgKey, String topic, String payload,
                          Instant deliverAt, int retryCount) {}
}
