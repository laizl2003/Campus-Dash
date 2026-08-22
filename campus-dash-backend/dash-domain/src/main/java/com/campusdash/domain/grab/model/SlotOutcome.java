package com.campusdash.domain.grab.model;

/**
 * Redis Lua 原子判定的返回码。
 * 与 grab.lua 的返回值严格一对一，改脚本必须同步改这里。
 */
public enum SlotOutcome {

    /** 占位成功，可以进入 DB 落库 */
    ACQUIRED(1),
    /** 名额已满，进候选队列 */
    SLOT_FULL(0),
    /** 同一用户重复抢（INV-2） */
    ALREADY_GRABBED(-1),
    /** 同一 requestId 重复提交，返回首次结果（幂等） */
    DUPLICATE_REQUEST(-2),
    /** 任务不可抢（不存在或状态不对） */
    NOT_GRABBABLE(-3);

    private final long code;

    SlotOutcome(long code) {
        this.code = code;
    }

    public long code() {
        return code;
    }

    public static SlotOutcome fromCode(long code) {
        for (SlotOutcome o : values()) {
            if (o.code == code) {
                return o;
            }
        }
        throw new IllegalArgumentException("未知的 Lua 返回码: " + code);
    }
}
