package com.campusdash.infrastructure.config;

import com.campusdash.domain.errand.ports.DelayMessagePort;
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
 * 延迟消息端口的 RocketMQ 实现。
 *
 * 用 5.x 的 setDeliveryTimestamp 做任意时间定时消息——这是选 5.x 客户端的唯一理由。
 * 4.x 的 remoting 客户端只有 18 个固定 delay level（1s/5s/10s/30s/1m/2m...），
 * 满足不了"5 分钟后精确投递"，更别提业务上可能要的任意时长。
 *
 * 注意 topic 必须是 DELAY 类型，否则 broker 会拒绝定时消息：
 *   mqadmin updateTopic -t errand-confirm-timeout -a +message.type=DELAY
 * 这一步在 docker/init-mq.sh 里做。
 */
@Component
@ConditionalOnProperty(name = "dash.mq.enabled", havingValue = "true")
public class RocketMqDelayMessageAdapter implements DelayMessagePort {

    private static final Logger log = LoggerFactory.getLogger(RocketMqDelayMessageAdapter.class);

    private final ClientServiceProvider provider;
    private final Producer producer;

    public RocketMqDelayMessageAdapter(ClientServiceProvider provider, Producer producer) {
        this.provider = provider;
        this.producer = producer;
    }

    @Override
    public void send(String topic, String msgKey, String payload, Instant deliverAt) {
        try {
            Message message = provider.newMessageBuilder()
                    .setTopic(topic)
                    // msgKey 既是业务幂等键，也让消息在控制台可按 key 检索，排障方便
                    .setKeys(msgKey)
                    .setBody(payload.getBytes(StandardCharsets.UTF_8))
                    .setDeliveryTimestamp(deliverAt.toEpochMilli())
                    .build();
            var receipt = producer.send(message);
            log.debug("定时消息已投递 msgKey={} msgId={} deliverAt={}", msgKey, receipt.getMessageId(), deliverAt);
        } catch (Exception e) {
            // 抛 RuntimeException 让上层感知：消息仍是 PENDING，worker 会重发
            throw new IllegalStateException("定时消息发送失败 msgKey=" + msgKey, e);
        }
    }
}
