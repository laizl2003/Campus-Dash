package com.campusdash.infrastructure.config;

import com.campusdash.domain.voucher.ports.VoucherClaimPublisher;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 领取事件：Redis 预扣成功后投递，worker 消费落库 */
@Component
@ConditionalOnProperty(name = "dash.mq.enabled", havingValue = "true")
public class RocketMqVoucherClaimPublisher implements VoucherClaimPublisher {

    private final ClientServiceProvider provider;
    private final Producer producer;
    private final String topic;

    public RocketMqVoucherClaimPublisher(ClientServiceProvider provider, Producer producer,
                                         @Value("${dash.mq.topic.voucher-claim:voucher-claim}") String topic) {
        this.provider = provider;
        this.producer = producer;
        this.topic = topic;
    }

    @Override
    public void publish(long voucherId, int type, String userId, String expireAt, String description) {
        String body = String.format(
                "{\"voucherId\":%d,\"type\":%d,\"userId\":\"%s\",\"expireAt\":\"%s\",\"description\":\"%s\"}",
                voucherId, type, userId, expireAt, description == null ? "" : description.replace("\"", "\\\""));
        Message message = provider.newMessageBuilder()
                .setTopic(topic)
                .setKeys(String.valueOf(voucherId))
                .setBody(body.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .build();
        try {
            producer.send(message);
        } catch (Exception e) {
            throw new IllegalStateException("优惠券领取消息发送失败 voucherId=" + voucherId, e);
        }
    }
}
