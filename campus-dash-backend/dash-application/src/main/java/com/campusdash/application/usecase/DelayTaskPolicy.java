package com.campusdash.application.usecase;

import java.time.Instant;

/**
 * 延迟任务的 topic 与幂等键约定。
 *
 * P2 只有一种延迟任务（确认超时），P3 增加"送达后 24h 自动结算"，所以从
 * TimeoutPolicy 泛化成这里——两类延迟任务的 msg_key 前缀必须区分开，
 * 否则 local_message 的唯一索引会让它们互相顶掉。
 *
 * 为什么延迟任务只能走"本地消息表 + 定时消息"而不能用事务消息：
 * RocketMQ 的事务消息与定时消息互斥，事务消息不支持 setDeliveryTimestamp。
 * 需要"延迟 + 与事务一致"时，本地消息表是唯一选择。
 * 反过来，不需要延迟的资金事件通知用事务消息更简洁（省一张表和一个重发 job）。
 */
public final class DelayTaskPolicy {

    /** 确认超时流转（DELAY 类型 topic） */
    public static final String TOPIC_CONFIRM_TIMEOUT = "errand-confirm-timeout";
    /** 送达后自动结算（DELAY 类型 topic） */
    public static final String TOPIC_AUTO_SETTLE = "errand-auto-settle";
    /** 到期无人接单则过期（DELAY 类型 topic） */
    public static final String TOPIC_ERRAND_EXPIRE = "errand-expire";

    private DelayTaskPolicy() {
    }

    /** 超时流转幂等键：带 round 才能识别旧轮次消息 */
    public static String timeoutKey(long errandId, int round) {
        return "timeout:" + errandId + ":" + round;
    }

    /** 自动结算幂等键：一个任务只会送达一次，不需要轮次 */
    public static String autoSettleKey(long errandId) {
        return "autosettle:" + errandId;
    }

    /**
     * 过期幂等键。
     *
     * 带 expiredAt 的 epoch 毫秒：用户修改过期时间后重新发布时，
     * 新旧两条消息的 key 不同，不会被 unique 索引顶掉，
     * 而消费端最终以数据库里的 expired_at 为准判断是否需要过期。
     */
    public static String expireKey(long errandId, Instant expiredAt) {
        return "expire:" + errandId + ":" + expiredAt.toEpochMilli();
    }

    /** 过期消息体：只需要 errandId，其余以数据库为准 */
    public static String expirePayload(long errandId) {
        return String.format("{\"errandId\":%d}", errandId);
    }

    /** 消息体只带定位与幂等判定所需字段，不塞业务快照（会过期） */
    public static String payload(long errandId, int round, long version) {
        return String.format("{\"errandId\":%d,\"round\":%d,\"version\":%d}", errandId, round, version);
    }

    /**
     * 自动结算消息体：与超时流转的 payload 字段不同（没有 round/version），
     * 所以单独定义——两类消息的消费者各自解析自己的格式，
     * 不能共用一个 payload 方法（P4 实测：共用导致消费者解析出空串抛异常）
     */
    public static String autoSettlePayload(long errandId) {
        return String.format("{\"errandId\":%d}", errandId);
    }
}
