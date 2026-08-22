package com.campusdash.infrastructure.cache;

import com.campusdash.domain.errand.ports.CacheEvictDelayPort;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * 延迟双删的 RocketMQ 定时消息实现。
 *
 * 复用 timeoutProducer（普通 Producer 可发定时消息，只有事务消息需要专用 Producer——
 * 这是 P4 踩坑第 12 条的教训：事务消息要 TransactionChecker 与 TRANSACTION 类型 topic，
 * 定时消息不需要，两者不能混用同一个 Producer 但可以共用普通 Producer）。
 */
@Component
@ConditionalOnProperty(name = "dash.mq.enabled", havingValue = "true")
public class RocketMqCacheEvictAdapter implements CacheEvictDelayPort {

    private static final Logger log = LoggerFactory.getLogger(RocketMqCacheEvictAdapter.class);

    private final Producer producer;
    private final ClientServiceProvider provider;

    public RocketMqCacheEvictAdapter(Producer timeoutProducer, ClientServiceProvider provider) {
        this.producer = timeoutProducer;
        this.provider = provider;
    }

    @Override
    public void scheduleEvict(long errandId, Instant deliverAt) {
        try {
            Message msg = provider.newMessageBuilder()
                    .setTopic(CacheEvictDelayPort.TOPIC_CACHE_EVICT)
                    .setKeys("evict:" + errandId)
                    .setBody(("{\"errandId\":" + errandId + "}").getBytes(StandardCharsets.UTF_8))
                    .setDeliveryTimestamp(deliverAt.toEpochMilli())
                    .build();
            producer.send(msg);
        } catch (ClientException e) {
            // 双删发送失败不影响业务：第一次删除已完成，TTL 与校验 job 兜底
            log.warn("延迟双删消息发送失败 errandId={}", errandId, e);
        }
    }
}
