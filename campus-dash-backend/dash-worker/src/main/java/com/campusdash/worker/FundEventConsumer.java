package com.campusdash.worker;

import com.campusdash.domain.notify.ports.NotificationRepository;
import com.campusdash.shared.SnowflakeIdGenerator;
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
 * 资金事件消费者：结算/退款/仲裁完成后落站内消息。
 *
 * 消息来自事务消息——事务消息保证"资金动作提交成功则消息必达"，
 * 这里只负责消费落库。消费端幂等靠 notification 表
 * uk_msg_user(msg_key, user_id) 唯一索引：MQ 是 at-least-once，
 * 重复消费时 insertIfAbsent 返回 false，照常 ACK。
 */
@Component
@ConditionalOnProperty(name = "dash.mq.enabled", havingValue = "true")
public class FundEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(FundEventConsumer.class);

    private final ClientServiceProvider provider;
    private final ClientConfiguration configuration;
    private final NotificationRepository notificationRepository;
    private final SnowflakeIdGenerator idGenerator;
    private final String topic;
    private final String group;

    private PushConsumer consumer;

    public FundEventConsumer(ClientServiceProvider provider,
                             ClientConfiguration configuration,
                             NotificationRepository notificationRepository,
                             SnowflakeIdGenerator idGenerator,
                             @Value("${dash.mq.topic.fund-event:errand-fund-event}") String topic,
                             @Value("${dash.mq.group.fund-event:dash-fund-event-consumer}") String group) {
        this.provider = provider;
        this.configuration = configuration;
        this.notificationRepository = notificationRepository;
        this.idGenerator = idGenerator;
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
                    String msgKey = msg.getKeys().stream().findFirst()
                            .orElse(msg.getMessageId().toString());
                    String tag = msg.getTag().orElse("UNKNOWN");
                    try {
                        long errandId = extractLong(body, "errandId");
                        long publisherId = extractLong(body, "publisherId");
                        long runnerId = extractLong(body, "runnerId");
                        long amount = extractLong(body, "amountCents");

                        // 发单人视角与跑腿视角的文案不同——同一事件对双方语义不一样，
                        // 不能共用一句话（P4 浏览器验证时发现"你获得"出现在发单人侧）
                        String pubContent = switch (tag) {
                            case "SETTLED" -> String.format("任务已完成结算，跑腿获得 %d 分", amount);
                            case "REFUNDED" -> String.format("任务已退款，%d 分已退回你的账户", amount);
                            case "ARBITRATED" -> String.format("争议已裁决，涉及金额 %d 分", amount);
                            default -> "任务 " + errandId + " 资金状态变动";
                        };
                        notificationRepository.insertIfAbsent(idGenerator.nextId(),
                                msgKey + ":pub", publisherId, errandId, tag, pubContent);

                        // 结算类事件同时通知跑腿
                        if (runnerId > 0 && ("SETTLED".equals(tag) || "ARBITRATED".equals(tag))) {
                            String runnerContent = String.format("任务结算完成，你获得 %d 分", amount);
                            notificationRepository.insertIfAbsent(idGenerator.nextId(),
                                    msgKey + ":runner", runnerId, errandId, tag, runnerContent);
                        }
                        log.info("资金事件已消费 msgKey={} tag={} errandId={}", msgKey, tag, errandId);
                        return ConsumeResult.SUCCESS;
                    } catch (RuntimeException e) {
                        log.error("资金事件消费失败，交由 MQ 重试 msgKey={} body={}", msgKey, body, e);
                        return ConsumeResult.FAILURE;
                    }
                })
                .build();
        log.info("资金事件消费者已启动 topic={} group={}", topic, group);
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
        int end = i;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        return Long.parseLong(json.substring(i, end));
    }
}
