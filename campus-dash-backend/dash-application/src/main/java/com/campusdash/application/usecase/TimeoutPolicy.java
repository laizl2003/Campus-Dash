package com.campusdash.application.usecase;

/**
 * @deprecated P3 起改用 {@link DelayTaskPolicy}——延迟任务不止"确认超时"一种了。
 * 保留本类只为不破坏既有引用，方法体全部委托过去，不再承载任何自己的常量。
 */
@Deprecated(since = "P3")
public final class TimeoutPolicy {

    public static final String TOPIC_CONFIRM_TIMEOUT = DelayTaskPolicy.TOPIC_CONFIRM_TIMEOUT;

    private TimeoutPolicy() {
    }

    public static String msgKey(long errandId, int round) {
        return DelayTaskPolicy.timeoutKey(errandId, round);
    }

    public static String payload(long errandId, int round, long version) {
        return DelayTaskPolicy.payload(errandId, round, version);
    }
}
