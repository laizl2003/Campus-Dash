package com.campusdash.shared;

/**
 * 业务错误码。抢单是高失败率场景（N 抢 1 时绝大多数请求都会失败），
 * 所以"抢不到"不是异常而是正常返回码，只有真正的异常状况才抛异常。
 */
public enum ErrorCode {

    OK("成功"),

    // 抢单结果码
    SLOT_FULL("名额已满"),
    ALREADY_GRABBED("你已抢过该任务"),
    ERRAND_NOT_GRABBABLE("任务当前不可抢"),
    DUPLICATE_REQUEST("重复请求"),
    GRAB_CONFLICT("并发冲突，抢单失败"),
    GRAB_RATE_LIMITED("当前任务过热，请稍后再试"),

    // 资格
    CREDIT_TOO_LOW("信用分不足"),
    TOO_MANY_ONGOING("在途任务数已达上限"),

    // 资金
    INSUFFICIENT_BALANCE("余额不足"),
    ESCROW_ALREADY_EXISTS("该任务已托管"),
    ESCROW_NOT_FOUND("托管单不存在"),
    ESCROW_NOT_HELD("托管单已结算或已退款"),
    ALREADY_SETTLED("任务已结算"),
    ALREADY_REFUNDED("任务已退款"),
    SETTLE_CONFLICT("并发冲突，结算失败"),
    REFUND_CONFLICT("并发冲突，退款失败"),

    // 确认与流转
    NOT_CURRENT_GRABBER("你不是当前抢中者"),
    ALREADY_CONFIRMED("任务已确认"),
    TRANSFER_SKIPPED("流转已失效（状态或轮次不匹配）"),
    NO_CANDIDATE("候选队列为空"),

    // 状态与权限
    NOT_PUBLISHER("你不是该任务的发单人"),
    NOT_ARBITRABLE("任务当前不可仲裁"),

    // 通用
    UNAUTHORIZED("未登录或会话已过期"),
    ERRAND_NOT_FOUND("任务不存在"),
    ACCOUNT_NOT_FOUND("账户不存在"),
    ILLEGAL_STATE_TRANSITION("非法状态流转"),
    STALE_VERSION("数据已被并发修改"),
    INTERNAL_ERROR("系统内部错误");

    private final String message;

    ErrorCode(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}
