package com.campusdash.worker;

import com.campusdash.domain.voucher.ports.VoucherRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.FilterExpressionType;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

/**
 * 优惠券到期消费者。
 *
 * 消息由领取时登记的本地消息表在到期时刻投递（dash.voucher.expire-hours，默认 48 小时）。
 * 幂等靠 markExpired 的 WHERE status='available'：重复投递、已被使用都不会误改。
 */
@Component
@ConditionalOnProperty(name = "dash.mq.enabled", havingValue = "true")
public class VoucherExpireConsumer {

    private static final Logger log = LoggerFactory.getLogger(VoucherExpireConsumer.class);

    private final ClientServiceProvider provider;
    private final ClientConfiguration configuration;
    private final VoucherRepository voucherRepository;
    private final String topic;
    private final String group;

    private PushConsumer consumer;

    public VoucherExpireConsumer(ClientServiceProvider provider,
                                 ClientConfiguration configuration,
                                 VoucherRepository voucherRepository,
                                 @Value("${dash.mq.topic.voucher-expire:voucher-expire}") String topic,
                                 @Value("${dash.mq.group.voucher-expire:dash-voucher-consumer}") String group) {
        this.provider = provider;
        this.configuration = configuration;
        this.voucherRepository = voucherRepository;
        this.topic = topic;
        this.group = group;
    }

    @PostConstruct
    public void start() throws Exception {
        consumer = provider.newPushConsumerBuilder()
                .setClientConfiguration(configuration)
                .setConsumerGroup(group)
                .setSubscriptionExpressions(Collections.singletonMap(
                        topic, new FilterExpression("*", FilterExpressionType.TAG)))
                .setMessageListener(msg -> {
                    String body = StandardCharsets.UTF_8.decode(msg.getBody()).toString();
                    try {
                        long voucherId = extractLong(body, "voucherId");
                        int updated = voucherRepository.markExpired(voucherId);
                        log.info("优惠券到期处理 voucherId={} changed={}", voucherId, updated);
                        return ConsumeResult.SUCCESS;
                    } catch (IllegalArgumentException e) {
                        // 毒消息：重试也不会成功，ACK 丢弃
                        log.error("优惠券到期毒消息，丢弃 body={}", body, e);
                        return ConsumeResult.SUCCESS;
                    } catch (RuntimeException e) {
                        log.error("优惠券到期处理失败，交由 MQ 重试 voucherBody={}", body, e);
                        return ConsumeResult.FAILURE;
                    }
                })
                .build();
        log.info("优惠券到期消费者已启动 topic={} group={}", topic, group);
    }

    @PreDestroy
    public void stop() throws Exception {
        if (consumer != null) {
            consumer.close();
        }
    }

    private static long extractLong(String json, String field) {
        String needle = "\"" + field + "\":";
        int start = json.indexOf(needle);
        if (start < 0) {
            throw new IllegalArgumentException("消息体缺少字段 " + field + ": " + json);
        }
        int i = start + needle.length();
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
            i++;
        }
        int end = i;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        String value = json.substring(i, end);
        if (value.isEmpty()) {
            throw new IllegalArgumentException("字段 " + field + " 值为空: " + json);
        }
        return Long.parseLong(value);
    }
}
