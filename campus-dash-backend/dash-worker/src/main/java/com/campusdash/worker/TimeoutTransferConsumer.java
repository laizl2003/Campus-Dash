package com.campusdash.worker;

import com.campusdash.application.usecase.TimeoutTransferUseCase;
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
 * 超时消息消费者——超时流转的主通道。
 *
 * 消息可能重复投递（MQ 语义是 at-least-once），所以这里完全依赖
 * TimeoutTransferUseCase 的幂等三件套，消费端自己不做去重判断。
 *
 * 异常处理原则：业务判定为"已失效"的消息要 ACK（SUCCESS），否则会无限重投；
 * 只有真正的系统异常才返回 FAILURE 让 MQ 重试。把这两者搞混是消费端最常见的错误——
 * 前者会导致同一条消息永远在重试队列里打转。
 */
@Component
@ConditionalOnProperty(name = "dash.mq.enabled", havingValue = "true")
public class TimeoutTransferConsumer {

    private static final Logger log = LoggerFactory.getLogger(TimeoutTransferConsumer.class);

    private final ClientServiceProvider provider;
    private final ClientConfiguration configuration;
    private final TimeoutTransferUseCase timeoutTransferUseCase;
    private final String topic;
    private final String group;

    private PushConsumer consumer;

    public TimeoutTransferConsumer(ClientServiceProvider provider,
                                   ClientConfiguration configuration,
                                   TimeoutTransferUseCase timeoutTransferUseCase,
                                   @Value("${dash.mq.topic.confirm-timeout:errand-confirm-timeout}") String topic,
                                   @Value("${dash.mq.group.confirm-timeout:dash-timeout-consumer}") String group) {
        this.provider = provider;
        this.configuration = configuration;
        this.timeoutTransferUseCase = timeoutTransferUseCase;
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
                        int round = (int) extractLong(body, "round");
                        var outcome = timeoutTransferUseCase.handleTimeout(errandId, round);
                        log.debug("超时消息处理完成 errandId={} round={} outcome={}", errandId, round, outcome);
                        // 无论 TRANSFERRED / REVERTED / SKIPPED 都要 ACK：
                        // SKIPPED 是幂等生效的正常结果，不 ACK 会导致消息无限重投
                        return ConsumeResult.SUCCESS;
                    } catch (RuntimeException e) {
                        // 只有系统异常才交给 MQ 重试
                        log.error("超时消息处理异常，交由 MQ 重试 body={}", body, e);
                        return ConsumeResult.FAILURE;
                    }
                })
                .build();
        log.info("超时消息消费者已启动 topic={} group={}", topic, group);
    }

    @PreDestroy
    public void stop() throws Exception {
        if (consumer != null) {
            consumer.close();
        }
    }

    /**
     * 极简 JSON 取值。
     * 消息体是我们自己产生的固定格式（TimeoutPolicy.payload），
     * 为此引入完整 JSON 库不值得——依赖越少，worker 启动越快、故障面越小。
     */
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
