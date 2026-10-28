package com.campusdash.worker;

import com.campusdash.domain.errand.ports.LocalMessageRepository;
import com.campusdash.domain.voucher.model.Voucher;
import com.campusdash.domain.voucher.model.VoucherStatus;
import com.campusdash.domain.voucher.ports.VoucherClaimPort;
import com.campusdash.domain.voucher.ports.VoucherRepository;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;

/** 优惠券领取消费者：DB 落库 + 扣库存 + 登记到期消息；失败重试，Redis 预扣等待对账/回滚 */
@Component
@ConditionalOnProperty(name = "dash.mq.enabled", havingValue = "true")
public class VoucherClaimConsumer {

    private static final Logger log = LoggerFactory.getLogger(VoucherClaimConsumer.class);
    private static final String EXPIRE_TOPIC = "voucher-expire";

    private final ClientServiceProvider provider;
    private final ClientConfiguration configuration;
    private final VoucherRepository vouchers;
    private final VoucherClaimPort cache;
    private final LocalMessageRepository localMessages;
    private final SnowflakeIdGenerator idGenerator;
    private final TransactionTemplate transactionTemplate;
    private final String topic;
    private final String group;

    private PushConsumer consumer;

    public VoucherClaimConsumer(ClientServiceProvider provider,
                                ClientConfiguration configuration,
                                VoucherRepository vouchers,
                                VoucherClaimPort cache,
                                LocalMessageRepository localMessages,
                                SnowflakeIdGenerator idGenerator,
                                TransactionTemplate transactionTemplate,
                                @Value("${dash.mq.topic.voucher-claim:voucher-claim}") String topic,
                                @Value("${dash.mq.group.voucher-claim:dash-voucher-claim}") String group) {
        this.provider = provider;
        this.configuration = configuration;
        this.vouchers = vouchers;
        this.cache = cache;
        this.localMessages = localMessages;
        this.idGenerator = idGenerator;
        this.transactionTemplate = transactionTemplate;
        this.topic = topic;
        this.group = group;
    }

    @PostConstruct
    public void start() {
        tryStart();
    }

    /** topic 可能由第一条消息首次创建；启动失败不拖垮 worker，30 秒后重试订阅 */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 30000)
    public void ensureStarted() {
        if (consumer == null) tryStart();
    }

    private synchronized void tryStart() {
        if (consumer != null) return;
        try {
        consumer = provider.newPushConsumerBuilder()
                .setClientConfiguration(configuration)
                .setConsumerGroup(group)
                .setSubscriptionExpressions(Collections.singletonMap(
                        topic, new FilterExpression("*", FilterExpressionType.TAG)))
                .setMessageListener(msg -> {
                    String body = StandardCharsets.UTF_8.decode(msg.getBody()).toString();
                    long voucherId;
                    int type;
                    String userId;
                    Instant expireAt;
                    try {
                        voucherId = extractLong(body, "voucherId");
                        type = (int) extractLong(body, "type");
                        userId = extractString(body, "userId");
                        expireAt = Instant.parse(extractString(body, "expireAt"));
                    } catch (RuntimeException e) {
                        log.error("优惠券领取消息格式错误 body={}", body, e);
                        return ConsumeResult.SUCCESS; // 毒消息不重试
                    }

                    try {
                        if (vouchers.findById(voucherId).isPresent()) {
                            cache.confirm(type, voucherId);
                            return ConsumeResult.SUCCESS;
                        }
                        boolean ok = Boolean.TRUE.equals(transactionTemplate.execute(status -> {
                            if (vouchers.countAvailable(userId, type, Instant.now()) > 0) {
                                return false; // 已持有可用券：回滚预扣
                            }
                            if (vouchers.insertIfAbsent(new Voucher(voucherId, userId, type, expireAt,
                                    VoucherStatus.AVAILABLE, null, Instant.now())) == 0) {
                                return true; // 已落库，幂等
                            }
                            if (vouchers.decrementStock(type) != 1) {
                                vouchers.deleteById(voucherId);
                                return false;
                            }
                            localMessages.enqueue(idGenerator.nextId(), "voucherexpire:" + voucherId,
                                    EXPIRE_TOPIC, "{\"voucherId\":" + voucherId + "}", expireAt);
                            return true;
                        }));
                        if (ok) {
                            cache.confirm(type, voucherId);
                        } else {
                            cache.rollback(type, userId, voucherId);
                        }
                        return ConsumeResult.SUCCESS;
                    } catch (RuntimeException e) {
                        // 重试 5 次仍失败：落库成功则确认 pending，否则回滚 Redis，避免名额永久挂死
                        if (msg.getDeliveryAttempt() >= 5) {
                            if (vouchers.findById(voucherId).isPresent()) {
                                cache.confirm(type, voucherId);
                            } else {
                                cache.rollback(type, userId, voucherId);
                            }
                            log.error("优惠券领取达到重试上限，已补偿 voucherId={}", voucherId, e);
                            return ConsumeResult.SUCCESS;
                        }
                        log.error("优惠券领取落库失败，等待 MQ 重试 voucherId={}", voucherId, e);
                        return ConsumeResult.FAILURE;
                    }
                })
                .build();
        log.info("优惠券领取消费者已启动 topic={} group={}", topic, group);
        } catch (Exception e) {
            log.warn("优惠券领取消费者启动失败，30 秒后重试：{}", e.getMessage());
        }
    }

    @PreDestroy
    public void stop() throws Exception {
        if (consumer != null) consumer.close();
    }

    private static String extractString(String json, String field) {
        String needle = "\"" + field + "\":\"";
        int start = json.indexOf(needle);
        if (start < 0) throw new IllegalArgumentException("missing " + field);
        int from = start + needle.length();
        int to = json.indexOf('"', from);
        return json.substring(from, to);
    }

    private static long extractLong(String json, String field) {
        String needle = "\"" + field + "\":";
        int i = json.indexOf(needle);
        if (i < 0) throw new IllegalArgumentException("missing " + field);
        int start = i + needle.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
        return Long.parseLong(json.substring(start, end));
    }
}
