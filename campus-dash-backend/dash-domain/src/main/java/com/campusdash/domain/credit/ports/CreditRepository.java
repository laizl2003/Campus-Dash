package com.campusdash.domain.credit.ports;

import com.campusdash.domain.credit.model.CreditEvent;
import com.campusdash.domain.credit.model.CreditScore;

import java.util.List;
import java.util.Optional;

/**
 * 信用分仓储。
 *
 * applyEvent 是"写事件 + 更新快照"的原子动作，调用方必须放在业务事务内——
 * 这是"结算成功则信用分必变、无中间态"的保证来源（P5 验收标准第 5 条）。
 */
public interface CreditRepository {

    /** 查分数；用户没有记录时返回初始分 60（不落库，首次事件时才创建） */
    int scoreOf(long userId);

    Optional<CreditScore> find(long userId);

    /**
     * 应用信用事件：插入事件流水 + 更新分数快照。
     * biz_no 唯一索引冲突时返回 false（重复事件，幂等跳过）。
     */
    boolean applyEvent(CreditEvent event);

    /** 最近 N 天的事件（信用分页展示用） */
    List<CreditEvent> recentEvents(long userId, int days, int limit);

    /** 窗口内事件的分值总和（每日校准 job 用） */
    int windowDelta(long userId, int windowDays);

    /**
     * 按最近 windowDays 的事件重算信用分快照。
     *
     * @return 实际发生修正的用户数
     */
    int calibrateScores(int windowDays, int limit);
}
