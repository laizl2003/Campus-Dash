package com.campusdash.domain.grab.model;

import java.time.Instant;

/**
 * 抢单记录。
 * uk_errand_round_seq(errand_id, round, seq) 保证同一轮内一个名额只能被占一次（INV-1）；
 * uk_errand_round_user(errand_id, round, runner_id) 保证同一轮内一个用户只占一个名额（INV-2）。
 * 这两个唯一索引是防超卖的最后一道防线——即使 Redis 判错，数据库也不会让第二个人成功。
 *
 * 为什么索引里必须带 round：任务超时流转或回退后 round 自增，
 * 上一轮遗留的记录不能挡住新一轮的抢单。P1 时索引没带 round，
 * 单轮场景一切正常，P2 引入多轮流转后立刻暴露——回退后的任务谁都抢不到。
 */
public record GrabRecord(long id, long campusId, long errandId, long runnerId, int seq, int round,
                         GrabResultType result, Instant createdAt) {

    public static GrabRecord grabbed(long id, long campusId, long errandId, long runnerId, int seq, int round) {
        return new GrabRecord(id, campusId, errandId, runnerId, seq, round, GrabResultType.GRABBED, Instant.now());
    }

    public static GrabRecord candidate(long id, long campusId, long errandId, long runnerId, int round) {
        return new GrabRecord(id, campusId, errandId, runnerId, 0, round, GrabResultType.CANDIDATE, Instant.now());
    }

    public enum GrabResultType {
        GRABBED, CANDIDATE, EXPIRED
    }
}
