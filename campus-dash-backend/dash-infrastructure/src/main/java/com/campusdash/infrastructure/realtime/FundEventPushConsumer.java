package com.campusdash.infrastructure.realtime;

import com.campusdash.domain.notify.ports.RealtimeNotifier;
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
 * 资金事件的"推送消费者"：只负责把结算/退款结果实时推给相关用户，不做任何业务写入。
 *
 * ── 为什么 app 进程要再消费一遍资金事件 ──
 * 站内消息的落库消费在 worker 进程（FundEventConsumer），但 WebSocket 连接
 * 挂在 app 进程上，worker 推不到。所以 app 用独立消费组再订一遍同一个 topic：
 * 两个消费组互不影响，推送这条链路只读不写，天然幂等。
 *
 * 这是模块化单体"进程隔离"代价的一个实例：同一个事件要两个进程各消费一次。
 * 拆微服务后这类跨进程通知会统一走 MQ 广播，结构反而更清晰。
 */
@Component
@ConditionalOnProperty(name = "dash.mq.enabled", havingValue = "true")
public class FundEventPushConsumer {

    private static final Logger log = LoggerFactory.getLogger(FundEventPushConsumer.class);

    private final ClientServiceProvider provider;
    private final ClientConfiguration configuration;
    private final RealtimeNotifier notifier;
    private final String topic;
    private final String group;

    private PushConsumer consumer;

    public FundEventPushConsumer(ClientServiceProvider provider,
                                 ClientConfiguration configuration,
                                 RealtimeNotifier notifier,
                                 @Value("${dash.mq.topic.fund-event:errand-fund-event}") String topic,
                                 @Value("${dash.mq.group.fund-event-push:dash-fund-event-push}") String group) {
        this.provider = provider;
        this.configuration = configuration;
        this.notifier = notifier;
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
                        long publisherId = extractLong(body, "publisherId");
                        long runnerId = extractLong(body, "runnerId");
                        long amount = extractLong(body, "amountCents");
                        String tag = msg.getTag().orElse("");

                        String content = switch (tag) {
                            case "SETTLED" -> "任务已完成结算，跑腿获得 " + amount + " 分";
                            case "REFUNDED" -> "任务已退款，" + amount + " 分已退回你的账户";
                            case "ARBITRATED" -> "争议已裁决，涉及金额 " + amount + " 分";
                            default -> "任务资金状态变动";
                        };
                        // 推给双方（多端登录由 SessionRegistry 处理）
                        notifier.notificationArrived(publisherId, errandId, tag, content);
                        if (runnerId > 0) {
                            String runnerContent = "SETTLED".equals(tag)
                                    ? "任务结算完成，你获得 " + amount + " 分" : content;
                            notifier.notificationArrived(runnerId, errandId, tag, runnerContent);
                        }
                        return ConsumeResult.SUCCESS;
                    } catch (RuntimeException e) {
                        // 推送失败不重试：实时推送是体验优化，站内消息落库由 worker 保证
                        log.debug("资金事件推送失败（不影响落库）body={}", body);
                        return ConsumeResult.SUCCESS;
                    }
                })
                .build();
        log.info("资金事件推送消费者已启动 topic={} group={}", topic, group);
    }

    @PreDestroy
    public void stop() throws Exception {
        if (consumer != null) {
            consumer.close();
        }
    }

    private static long extractLong(String json, String field) {
        String needle = "\"" + field + "\":";
        int i = json.indexOf(needle) + needle.length();
        int end = i;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        String value = json.substring(i, end);
        return value.isEmpty() ? 0 : Long.parseLong(value);
    }
}
