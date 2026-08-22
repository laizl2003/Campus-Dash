package com.campusdash.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * worker 独立进程。
 *
 * 为什么不和在线服务跑在一个进程里：抢单是低延迟在线请求，
 * 超时流转、消息重发、后续的结算对账都是后台批量任务。放一起会互相拖累——
 * 对账 job 一跑，GC 抖动直接打到抢单的 P99 上。
 *
 * 拆开之后：资源隔离（worker 可单独调堆与 GC）、部署隔离（worker 重启不影响抢单）、
 * 扩缩容独立。业务逻辑复用 dash-application，不重复实现。
 */
@SpringBootApplication
@ComponentScan(basePackages = {
        "com.campusdash.worker",
        "com.campusdash.application",
        "com.campusdash.infrastructure"
})
@EnableScheduling
@EnableTransactionManagement
public class WorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkerApplication.class, args);
    }
}
