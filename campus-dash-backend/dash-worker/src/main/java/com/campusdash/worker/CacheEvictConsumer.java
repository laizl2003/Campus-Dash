package com.campusdash.worker;

import com.campusdash.domain.errand.ports.CacheEvictDelayPort;
import com.campusdash.domain.errand.ports.ErrandCachePort;
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
 * 延迟双删消费者：第二次删除的执行者。
 *
 * 写用例提交后：第一次删除立即执行（afterCommit），
 * 第二次删除延迟 500ms 由本消费者执行——覆盖"慢读在第一次删除后写回旧值"的窗口。
 *
 * 幂等：删除本身幂等（删不存在的 key 无副作用），重复消费无影响。
 */
@Component
@ConditionalOnProperty(name = "dash.mq.enabled", havingValue = "true")
public class CacheEvictConsumer {

    private static final Logger log = LoggerFactory.getLogger(CacheEvictConsumer.class);

    private final ClientServiceProvider provider;
    private final ClientConfiguration configuration;
    private final ErrandCachePort cache;
    private final String group;

    private PushConsumer consumer;

    public CacheEvictConsumer(ClientServiceProvider provider,
                              ClientConfiguration configuration,
                              ErrandCachePort cache,
                              @Value("${dash.mq.group.cache-evict:dash-cache-evict-consumer}") String group) {
        this.provider = provider;
        this.configuration = configuration;
        this.cache = cache;
        this.group = group;
    }

    @PostConstruct
    public void start() throws Exception {
        consumer = provider.newPushConsumerBuilder()
                .setClientConfiguration(configuration)
                .setConsumerGroup(group)
                .setSubscriptionExpressions(Collections.singletonMap(
                        CacheEvictDelayPort.TOPIC_CACHE_EVICT,
                        new FilterExpression("*", FilterExpressionType.TAG)))
                .setMessageListener(msg -> {
                    String body = StandardCharsets.UTF_8.decode(msg.getBody()).toString();
                    try {
                        long errandId = extractLong(body, "errandId");
                        cache.evict(errandId);
                        log.debug("延迟双删第二次删除完成 errandId={}", errandId);
                        return ConsumeResult.SUCCESS;
                    } catch (IllegalArgumentException e) { // NumberFormatException 是其子类
                        // 毒消息：重试无意义，ACK 丢弃（双删本身是加固手段，可丢）
                        log.error("延迟双删毒消息，丢弃 body={}", body, e);
                        return ConsumeResult.SUCCESS;
                    } catch (RuntimeException e) {
                        log.error("延迟双删消费失败 body={}", body, e);
                        return ConsumeResult.FAILURE;
                    }
                })
                .build();
        log.info("延迟双删消费者已启动 topic={} group={}", CacheEvictDelayPort.TOPIC_CACHE_EVICT, group);
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
        while (end < json.length() && Character.isDigit(json.charAt(end))) {
            end++;
        }
        String value = json.substring(i, end);
        if (value.isEmpty()) {
            throw new IllegalArgumentException("字段 " + field + " 值为空: " + json);
        }
        return Long.parseLong(value);
    }
}
