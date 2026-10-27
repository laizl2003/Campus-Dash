package com.campusdash.domain.credit.model;

import java.time.Instant;

/** 信用事件：一次加减分的事实记录（只增不改） */
public record CreditEvent(long id, String bizNo, String userId, CreditEventType type,
                          int delta, String refType, long refId, Instant createdAt) {

    public static String settleBizNo(long errandId) {
        return "settle:" + errandId;
    }

    /** 发单人的结算加分：与跑腿用不同 bizNo，避免唯一索引互相覆盖 */
    public static String settlePublisherBizNo(long errandId) {
        return "settle-pub:" + errandId;
    }

    public static String revertBizNo(long errandId, int round) {
        return "revert:" + errandId + ":" + round;
    }

    public static String cancelAfterGrabBizNo(long errandId) {
        return "cancelgrab:" + errandId;
    }

    public static String disputeLoseBizNo(long errandId, String loserId) {
        return "disputelose:" + errandId + ":" + loserId;
    }
}
