package com.campusdash.infrastructure.config;

import com.campusdash.domain.errand.ports.DelayMessagePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 延迟消息端口的空实现：只登记不投递。
 *
 * 用途有两个：
 *   1. RocketMQ 未启用时（dash.mq.enabled=false）应用与集成测试仍能跑通，
 *      超时流转退化为"纯靠 worker 兜底扫描"——这恰好证明了兜底通道的价值：
 *      主通道完全不可用时，业务只是变慢，不会卡死。
 *   2. 教程第 11 章对比延迟队列五种方案时，换实现不换业务代码的示例。
 *
 * 消息仍然写进了 local_message（PENDING），所以没有信息丢失。
 */
@Component
@ConditionalOnProperty(name = "dash.mq.enabled", havingValue = "false", matchIfMissing = true)
public class NoopDelayMessageAdapter implements DelayMessagePort {

    private static final Logger log = LoggerFactory.getLogger(NoopDelayMessageAdapter.class);

    @Override
    public void send(String topic, String msgKey, String payload, Instant deliverAt) {
        log.debug("[noop-mq] 未启用 MQ，消息仅登记在 local_message，由兜底扫描处理 msgKey={} deliverAt={}",
                msgKey, deliverAt);
    }
}
