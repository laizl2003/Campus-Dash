package com.campusdash.worker;

import com.campusdash.application.usecase.ExpireErrandUseCase;
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
 * 任务过期消费者：到点仍未接单，PUBLISHED -> EXPIRED。
 *
 * 消息可能重复投递、可能提前到达、也可能任务已被抢走，
 * 所以消费端一律重新查库判断（ExpireErrandUseCase 里做），
 * 这里只负责解析和 ACK 语义：
 *   - 业务上无需处理（已抢走/已过期/未到点）也要 ACK，否则消息会无限重投；
 *   - 只有真正的系统异常才 FAILURE 交给 MQ 重试。
 */
@Component
@ConditionalOnProperty(name = "dash.mq.enabled", havingValue = "true")
public class ErrandExpireConsumer {

    private static final Logger log = LoggerFactory.getLogger(ErrandExpireConsumer.class);

    private final ClientServiceProvider provider;
    private final ClientConfiguration configuration;
    private final ExpireErrandUseCase expireUseCase;
    private final String topic;
    private final String group;

    private PushConsumer consumer;

    public ErrandExpireConsumer(ClientServiceProvider provider,
                                ClientConfiguration configuration,
                                ExpireErrandUseCase expireUseCase,
                                @Value("${dash.mq.topic.errand-expire:errand-expire}") String topic,
                                @Value("${dash.mq.group.errand-expire:dash-errand-expire-consumer}") String group) {
        this.provider = provider;
        this.configuration = configuration;
        this.expireUseCase = expireUseCase;
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
                .setMessageListener(view -> {
                    String body = StandardCharsets.UTF_8.decode(view.getBody()).toString();
                    try {
                        long errandId = extractLong(body, "errandId");
                        var outcome = expireUseCase.expire(errandId);
                        log.debug("过期消息处理完成 errandId={} outcome={}", errandId, outcome);
                        return ConsumeResult.SUCCESS;
                    } catch (RuntimeException e) {
                        log.error("过期消息处理异常，交由 MQ 重试 body={}", body, e);
                        return ConsumeResult.FAILURE;
                    }
                })
                .build();
        log.info("任务过期消费者已启动 topic={} group={}", topic, group);
    }

    @PreDestroy
    public void stop() throws Exception {
        if (consumer != null) {
            consumer.close();
        }
    }

    /** 与超时消费者相同的极简 JSON 取值，避免为一个字段引入 JSON 依赖 */
    private static long extractLong(String json, String field) {
        String needle = "\"" + field + "\":";
        int start = json.indexOf(needle);
        if (start < 0) {
            throw new IllegalArgumentException("消息体缺少字段 " + field + ": " + json);
        }
        int i = start + needle.length();
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
