package com.campusdash.domain.errand.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * 任务状态机：用枚举 + 允许流转集合表达，而不是散落各处的 if-else。
 * 新增状态只改这里，业务代码不用动。
 *
 * 注意 LOCKED -> LOCKED 是合法自环：代表"抢中者确认超时，流转给候选队列下一位"，
 * 靠 Errand.round 区分轮次。这是本业务的特色分支。
 *
 * 一期只落地到 LOCKED/ACCEPTED；PICKED_UP 之后的流转在 P2/P3 补齐，
 * 但状态与流转规则在一期就定义完整，避免后续改动状态机本身。
 */
public enum ErrandStatus {

    DRAFT,
    PUBLISHED,
    LOCKED,
    ACCEPTED,
    PICKED_UP,
    DELIVERED,
    SETTLED,
    CLOSED,
    CANCELLED,
    DISPUTED,
    REFUNDED;

    private static final Set<ErrandStatus> FROM_DRAFT = EnumSet.of(PUBLISHED, CANCELLED);
    private static final Set<ErrandStatus> FROM_PUBLISHED = EnumSet.of(LOCKED, CANCELLED);
    private static final Set<ErrandStatus> FROM_LOCKED = EnumSet.of(ACCEPTED, LOCKED, PUBLISHED, CANCELLED);
    private static final Set<ErrandStatus> FROM_ACCEPTED = EnumSet.of(PICKED_UP, DISPUTED, CANCELLED);
    private static final Set<ErrandStatus> FROM_PICKED_UP = EnumSet.of(DELIVERED, DISPUTED);
    private static final Set<ErrandStatus> FROM_DELIVERED = EnumSet.of(SETTLED, DISPUTED);
    private static final Set<ErrandStatus> FROM_DISPUTED = EnumSet.of(SETTLED, REFUNDED);
    private static final Set<ErrandStatus> FROM_SETTLED = EnumSet.of(CLOSED);
    private static final Set<ErrandStatus> FROM_REFUNDED = EnumSet.of(CLOSED);
    private static final Set<ErrandStatus> TERMINAL = EnumSet.noneOf(ErrandStatus.class);

    public Set<ErrandStatus> allowedTargets() {
        return switch (this) {
            case DRAFT -> FROM_DRAFT;
            case PUBLISHED -> FROM_PUBLISHED;
            case LOCKED -> FROM_LOCKED;
            case ACCEPTED -> FROM_ACCEPTED;
            case PICKED_UP -> FROM_PICKED_UP;
            case DELIVERED -> FROM_DELIVERED;
            case DISPUTED -> FROM_DISPUTED;
            case SETTLED -> FROM_SETTLED;
            case REFUNDED -> FROM_REFUNDED;
            case CLOSED, CANCELLED -> TERMINAL;
        };
    }

    public boolean canTransitTo(ErrandStatus target) {
        return allowedTargets().contains(target);
    }

    /** 只有 PUBLISHED 状态的任务能被抢 */
    public boolean grabbable() {
        return this == PUBLISHED;
    }
}
