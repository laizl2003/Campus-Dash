package com.campusdash.domain.credit.model;

import java.time.Instant;

/** 信用事件：一次加减分的事实记录（只增不改） */
public record CreditEvent(long id, String bizNo, long userId, CreditEventType type,
                          int delta, String refType, long refId, Instant createdAt) {

    public static String settleBizNo(long errandId) {
        return "settle:" + errandId;
    }

    public static String revertBizNo(long errandId, int round) {
        return "revert:" + errandId + ":" + round;
    }

    public static String cancelAfterGrabBizNo(long errandId) {
        return "cancelgrab:" + errandId;
    }

    public static String disputeLoseBizNo(long errandId, long loserId) {
        return "disputelose:" + errandId + ":" + loserId;
    }
}
