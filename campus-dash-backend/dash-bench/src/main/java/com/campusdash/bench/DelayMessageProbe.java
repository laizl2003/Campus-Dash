package com.campusdash.bench;

import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.FilterExpressionType;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.producer.Producer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * RocketMQ 定时消息连通性探针（最小 demo）。
 *
 * 在写业务代码之前先跑这个：验证 broker 起得来、proxy 通、定时消息按时投递。
 * 不先验证的话，后面业务出问题时分不清是业务逻辑错了还是 MQ 根本没通——
 * 尤其 macOS 上 broker 会向客户端返回容器内网 IP，这个坑不提前踩会浪费很多时间。
 *
 * 用法：java -cp ... DelayMessageProbe [endpoint] [delaySeconds]
 */
public class DelayMessageProbe {

    private static final String TOPIC = "errand-confirm-timeout";
    private static final String GROUP = "dash-timeout-consumer";

    public static void main(String[] args) throws Exception {
        String endpoint = args.length > 0 ? args[0] : "127.0.0.1:8081";
        int delaySeconds = args.length > 1 ? Integer.parseInt(args[1]) : 5;

        ClientServiceProvider provider = ClientServiceProvider.loadService();
        ClientConfiguration config = ClientConfiguration.newBuilder()
                .setEndpoints(endpoint)
                .setRequestTimeout(Duration.ofSeconds(10))
                .build();

        String probeKey = "probe-" + System.currentTimeMillis();
        CountDownLatch received = new CountDownLatch(1);
        long[] receivedAt = new long[1];

        try (PushConsumer consumer = provider.newPushConsumerBuilder()
                .setClientConfiguration(config)
                .setConsumerGroup(GROUP)
                .setSubscriptionExpressions(Collections.singletonMap(
                        TOPIC, new FilterExpression("*", FilterExpressionType.TAG)))
                .setMessageListener(msg -> {
                    String keys = msg.getKeys().isEmpty() ? "" : msg.getKeys().iterator().next();
                    if (probeKey.equals(keys)) {
                        receivedAt[0] = System.currentTimeMillis();
                        received.countDown();
                    }
                    // 非本次探针的历史消息也要 ACK，避免堆积影响后续测试
                    return ConsumeResult.SUCCESS;
                })
                .build()) {

            long expectAt = System.currentTimeMillis() + delaySeconds * 1000L;
            try (Producer producer = provider.newProducerBuilder()
                    .setClientConfiguration(config)
                    .setTopics(TOPIC)
                    .build()) {
                Message message = provider.newMessageBuilder()
                        .setTopic(TOPIC)
                        .setKeys(probeKey)
                        .setBody(("{\"probe\":\"" + probeKey + "\"}").getBytes(StandardCharsets.UTF_8))
                        // 5.x 的任意时间定时消息：这是选 5.x 客户端的唯一理由，
                        // 4.x 只有 18 个固定 delay level，做不到"5 分钟后精确投递"
                        .setDeliveryTimestamp(expectAt)
                        .build();
                var receipt = producer.send(message);
                System.out.printf("[probe] 已发送定时消息 msgId=%s 期望投递=%d(+%ds)%n",
                        receipt.getMessageId(), expectAt, delaySeconds);
            }

            boolean ok = received.await(delaySeconds + 30L, TimeUnit.SECONDS);
            if (!ok) {
                System.err.println("[FAIL] 超时未收到定时消息，MQ 链路不通");
                System.exit(1);
            }
            long errorMs = receivedAt[0] - expectAt;
            System.out.printf("[probe] 已收到！实际投递=%d 误差=%dms%n", receivedAt[0], errorMs);
            if (Math.abs(errorMs) > 3000) {
                System.err.printf("[WARN] 投递误差 %dms 偏大，S5 准时率可能不达标%n", errorMs);
            }
            System.out.println("[PASS] RocketMQ 定时消息链路可用");
        }
    }
}
