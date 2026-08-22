package com.campusdash.domain.credit.model;

/**
 * 信用事件类型与分值规则。
 *
 * ── 为什么规则写在枚举里而不是配置 ──
 * 分值是业务规则不是环境参数：不同环境（测试/生产）用不同分值只会让
 * "信用分"这个概念失去确定性。要调整就走代码评审，留下变更记录。
 *
 * ── 滑动窗口 ──
 * 分数不是终身累积：每日 job 只按最近 30 天的事件重算。
 * 一次严重扣分半年后淡出，一次加分也不能吃一辈子——
 * 信用体系的可信度来自"会过期"，否则用户没有修复路径。
 */
public enum CreditEventType {

    SETTLE(+2, "完成结算"),
    GRAB_TIMEOUT_REVERT(-5, "抢中后超时未确认"),
    CANCEL_AFTER_GRAB(-10, "被抢单后取消（浪费跑腿时间）"),
    DELIVERY_LATE(-3, "送达超时"),
    DISPUTE_LOSE(-8, "争议败诉");

    private final int delta;
    private final String description;

    CreditEventType(int delta, String description) {
        this.delta = delta;
        this.description = description;
    }

    public int delta() {
        return delta;
    }

    public String description() {
        return description;
    }

    /** 滑动窗口：天 */
    public static final int WINDOW_DAYS = 30;
    /** 初始分 */
    public static final int BASE_SCORE = 60;
    /** 分数下限：防止负分无限叠加，也给用户留修复空间 */
    public static final int MIN_SCORE = 0;
    /** 分数上限 */
    public static final int MAX_SCORE = 100;
}
