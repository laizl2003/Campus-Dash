package com.campusdash.domain.credit.model;

/**
 * 信用分聚合。
 *
 * apply 负责把分数限制在 [MIN_SCORE, MAX_SCORE]：
 * 下限防止一次失误把用户打进负分深渊（抢单资格门槛是绝对值，
 * 负分与 0 分对资格判定没有区别，只会让展示难看）；
 * 上限防止刷单攒分——信用分的价值在约束，不在激励。
 */
public class CreditScore {

    private final long userId;
    private int score;
    private long version;

    public CreditScore(long userId, int score, long version) {
        this.userId = userId;
        this.score = score;
        this.version = version;
    }

    public static CreditScore initial(long userId) {
        return new CreditScore(userId, CreditEventType.BASE_SCORE, 0);
    }

    public void apply(int delta) {
        int next = this.score + delta;
        this.score = Math.max(CreditEventType.MIN_SCORE, Math.min(CreditEventType.MAX_SCORE, next));
        this.version++;
    }

    public long userId() { return userId; }
    public int score() { return score; }
    public long version() { return version; }
}
