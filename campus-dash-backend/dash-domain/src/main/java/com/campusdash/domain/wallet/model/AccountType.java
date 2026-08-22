package com.campusdash.domain.wallet.model;

/**
 * 账户类型。任何一笔资金流动都是在这三类账户之间搬钱，
 * 三方金额之和恒定不变，这是复式记账的基础。
 */
public enum AccountType {
    /** 用户账户 */
    USER,
    /** 平台托管账户（系统户，存所有在途任务的悬赏金） */
    ESCROW,
    /** 平台佣金账户（系统户） */
    COMMISSION
}
