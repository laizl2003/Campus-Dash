package com.campusdash.domain.grab.ports;

/**
 * 抢单热点限流端口（P7）。
 *
 * 业务层只关心"这个任务的抢单请求是否允许继续"，不绑定具体治理组件。
 * 生产可以接 Sentinel 热点参数限流；本地和测试可用固定窗口实现。
 */
public interface GrabRateLimiterPort {

    /**
     * @return true 表示允许继续进入信用校验、Redis Lua 和数据库 CAS；
     *         false 表示热点限流命中，应快速失败。
     */
    boolean tryPass(long errandId, long runnerId);
}
