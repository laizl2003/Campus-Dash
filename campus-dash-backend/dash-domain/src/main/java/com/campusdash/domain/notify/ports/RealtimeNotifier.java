package com.campusdash.domain.notify.ports;

/**
 * 实时推送端口（P5）。
 *
 * 用例在状态变更成功后调用，把事件推给相关用户的在线连接。
 * 实现是 presentation 层的 WebSocket 推送；推送失败绝不影响业务结果——
 * 实时性是体验层能力，事实以 DB 为准，前端还有轮询兜底。
 *
 * 已知边界：worker 进程触发的变更（超时流转、自动结算）推不到
 * app 进程的 WebSocket 连接（跨进程），由前端非终态低频轮询兜底。
 * 这是模块化单体的进程隔离代价，拆微服务后用 MQ 广播解决。
 */
public interface RealtimeNotifier {

    /** 任务状态变更：推给发单人与当前抢中者 */
    void errandStatusChanged(long errandId, long publisherId, Long grabberId,
                           String status, int round);

    /** 站内消息到达：推给指定用户 */
    void notificationArrived(long userId, long errandId, String type, String content);

    /** 信用分变更：推给本人 */
    void creditChanged(long userId, int newScore, int delta, String reason);
}
