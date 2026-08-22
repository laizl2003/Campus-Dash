package com.campusdash.worker;

import com.campusdash.application.usecase.SettleErrandUseCase;
import com.campusdash.domain.errand.model.Errand;
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
 * 自动结算消费者：送达后 24h 未确认，定时消息到期触发。
 *
 * 与超时流转消费者同一个形态：主通道（MQ 定时消息）+ 兜底（AutoSettleScanJob）。
 * 幂等完全交给 SettleErrandUseCase 的三道闸门，
 * 主通道与兜底同时到达也只会结算一次。
 */
@Component
@ConditionalOnProperty(name = "dash.mq.enabled", havingValue = "true")
public class AutoSettleConsumer {

    private static final Logger log = LoggerFactory.getLogger(AutoSettleConsumer.class);

    private final ClientServiceProvider provider;
    private final ClientConfiguration configuration;
    private final SettleErrandUseCase settleUseCase;
    private final String topic;
    private final String group;

    private PushConsumer consumer;

    public AutoSettleConsumer(ClientServiceProvider provider,
                              ClientConfiguration configuration,
                              SettleErrandUseCase settleUseCase,
                              @Value("${dash.mq.topic.auto-settle:errand-auto-settle}") String topic,
                              @Value("${dash.mq.group.auto-settle:dash-autosettle-consumer}") String group) {
        this.provider = provider;
        this.configuration = configuration;
        this.settleUseCase = settleUseCase;
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
                        long errandId = extractLong(body, "errandId");
                        var result = settleUseCase.settle(errandId, Errand.SYSTEM_OPERATOR);
                        log.info("自动结算消息处理 errandId={} result={}", errandId, result);
                        // ALREADY_SETTLED / CONFLICT 也是幂等生效的正常结果，都要 ACK
                        return ConsumeResult.SUCCESS;
                    } catch (IllegalArgumentException e) { // NumberFormatException 是其子类
                        // 毒消息（格式损坏）：重试永远不会成功，ACK 丢弃。
                        // 兜底扫描会按 DB 事实继续处理——"消息可丢、扫描不丢"
                        log.error("自动结算毒消息，丢弃 body={}", body, e);
                        return ConsumeResult.SUCCESS;
                    } catch (RuntimeException e) {
                        log.error("自动结算消息处理失败，交由 MQ 重试 body={}", body, e);
                        return ConsumeResult.FAILURE;
                    }
                })
                .build();
        log.info("自动结算消费者已启动 topic={} group={}", topic, group);
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
        if (i < json.length() && json.charAt(i) == '"') {
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
