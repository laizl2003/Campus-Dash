package com.campusdash.domain.grab.ports;

import java.util.Optional;

/**
 * 候选队列端口：抢单失败的人在这里排队，等抢中者确认超时后自动流转。
 * 用 Redis ZSET 实现，score = 抢单时间戳 - 信用分加权。
 *
 * 为什么用 ZSET 而不是 List：需要按分数取最优候选人，还要支持"用户主动退出候选"
 * 这种 O(log N) 的定位删除，List 做不到。
 */
public interface CandidateQueuePort {

    void offer(long errandId, long runnerId, double score);

    /** 弹出当前最优候选人（score 最小），P2 超时流转会用到 */
    Optional<Long> pollBest(long errandId);

    long size(long errandId);
}
