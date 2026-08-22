package com.campusdash.domain.wallet.ports;

/**
 * 资金事件发布端口。
 *
 * 为什么这个用事务消息而不是本地消息表：资金事件不需要延迟投递。
 * RocketMQ 的事务消息与定时消息互斥（事务消息不支持 setDeliveryTimestamp），
 * 所以需要延迟的场景（确认超时、自动结算）只能用本地消息表 + 定时消息，
 * 不需要延迟的场景用事务消息更简洁：省一张表和一个重发 job。
 *
 * 端口签名刻意不暴露"半消息""回查"这些 RocketMQ 概念——
 * 领域层只知道"执行这段本地事务，成功了就把事件发出去"。
 */
public interface FundEventPort {

    /**
     * 在事务消息保护下执行本地事务。
     *
     * @param event      要发布的资金事件
     * @param localWork  本地事务逻辑，返回 true 表示提交、false 表示回滚
     * @return localWork 的返回值
     */
    boolean publishInTransaction(FundEvent event, LocalWork localWork);

    /** 资金事件。bizNo 同时是 MQ 侧的消息 key 与回查依据 */
    record FundEvent(String bizNo, String type, long errandId, long publisherId,
                     long runnerId, long amountCents, long commissionCents) {}

    @FunctionalInterface
    interface LocalWork {
        boolean execute();
    }
}
