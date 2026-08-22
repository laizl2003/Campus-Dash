package com.campusdash.infrastructure.config;

import com.campusdash.domain.wallet.ports.FundEventPort;
import com.campusdash.domain.wallet.ports.WalletRepository;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.client.apis.producer.Transaction;
import org.apache.rocketmq.client.apis.producer.TransactionChecker;
import org.apache.rocketmq.client.apis.producer.TransactionResolution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

/**
 * 资金事件的事务消息实现。
 *
 * ── 事务消息的三段式 ──
 *   1. beginTransaction 拿到 Transaction 句柄，send 出去的是"半消息"（消费者看不到）
 *   2. 执行本地事务
 *   3. 本地事务成功就 commit（消息对消费者可见），失败就 rollback（消息丢弃）
 *
 * 第 3 步之前进程崩溃，消息会一直停在半消息状态，这时靠 broker 的回查
 * （checkLocalTransaction）来问"这笔本地事务到底成没成"。
 *
 * ── 为什么资金事件用事务消息，超时流转不用 ──
 * 事务消息与定时消息在 RocketMQ 里是互斥的：事务消息不支持 setDeliveryTimestamp。
 * 资金事件不需要延迟，用事务消息可以省掉本地消息表和重发 job；
 * 超时流转必须延迟 5 分钟投递，所以只能用本地消息表 + 定时消息。
 * 两者按"是否需要延迟"分工，不是两套重复实现。
 *
 * ── 回查的实现要点 ──
 * 回查逻辑只查一个确定性事实：wallet_ledger 里有没有这个 bizNo 的流水。
 * 绝不能查"中间状态"（比如任务状态），因为任务状态可能被后续操作改掉，
 * 而流水表只增不减，是可靠的判定依据。
 *
 * 注意 rocketmq-client-java 5.x 的 gRPC 客户端把回查做成了 TransactionChecker，
 * 在 Producer 构建时注册；与 4.x 的 TransactionListener 接口形态不同。
 */
public class RocketMqFundEventAdapter implements FundEventPort, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RocketMqFundEventAdapter.class);

    public static final String TOPIC_FUND_EVENT = "errand-fund-event";

    private Producer producer;
    private final ClientServiceProvider provider;
    private final WalletRepository walletRepository;

    public RocketMqFundEventAdapter(Producer producer, ClientServiceProvider provider,
                                    WalletRepository walletRepository) {
        this.producer = producer;
        this.provider = provider;
        this.walletRepository = walletRepository;
    }

    /** 构造时 producer 可为 null（checker 要先存在才能建 Producer），建成后再挂上 */
    public void attachProducer(Producer producer) {
        this.producer = producer;
    }

    /** 回查宽限期：半消息发出后这段时间内查不到流水，先返回 UNKNOWN 让 broker 再问 */
    private static final long CHECK_GRACE_MILLIS = 60_000;

    /**
     * 注册到 Producer 的回查器（5.x API 要求构建 Producer 时就必须给出 checker，
     * 缺了它 beginTransaction 直接抛 "Transaction checker should not be null"，实测踩过）。
     *
     * 三态判定：
     *   - 流水存在 → COMMIT（本地事务已提交）
     *   - 流水不存在且消息已超过宽限期 → ROLLBACK（本地事务大概率失败/回滚了）
     *   - 流水不存在但在宽限期内 → UNKNOWN（可能事务还没提交完，让 broker 稍后再问）
     */
    public TransactionChecker checker() {
        return (MessageView msg) -> {
            String bizNo = msg.getKeys().stream().findFirst().orElse("");
            boolean exists = checkLocalTransaction(bizNo);
            if (exists) {
                return TransactionResolution.COMMIT;
            }
            long age = System.currentTimeMillis() - msg.getBornTimestamp();
            if (age > CHECK_GRACE_MILLIS) {
                log.warn("回查判定 ROLLBACK：超过宽限期仍无流水 bizNo={} ageMs={}", bizNo, age);
                return TransactionResolution.ROLLBACK;
            }
            return TransactionResolution.UNKNOWN;
        };
    }

    @Override
    public boolean publishInTransaction(FundEvent event, LocalWork localWork) {
        Transaction transaction;
        Message half;
        try {
            transaction = producer.beginTransaction();
            half = provider.newMessageBuilder()
                    .setTopic(TOPIC_FUND_EVENT)
                    .setKeys(event.bizNo())
                    .setTag(event.type())
                    .setBody(toJson(event).getBytes(StandardCharsets.UTF_8))
                    .build();
            producer.send(half, transaction);
        } catch (ClientException e) {
            // 半消息都没发出去，本地事务不该执行——否则会出现"钱动了但事件永远发不出"
            log.error("半消息发送失败，放弃本次资金操作 bizNo={}", event.bizNo(), e);
            throw new IllegalStateException("半消息发送失败: " + event.bizNo(), e);
        }

        boolean committed = false;
        try {
            committed = localWork.execute();
            return committed;
        } finally {
            try {
                if (committed) {
                    transaction.commit();
                } else {
                    transaction.rollback();
                }
            } catch (ClientException e) {
                // commit/rollback 失败不影响本地事务的结果，broker 会回查兜底
                log.warn("事务消息 {} 失败，等待 broker 回查 bizNo={}",
                        committed ? "commit" : "rollback", event.bizNo(), e);
            }
        }
    }

    /**
     * 回查：本地事务到底成功没有。
     * 判定依据只有一个——流水表里有没有这个 bizNo。有就是成功（COMMIT），
     * 没有就是失败或还没写完（ROLLBACK，让消息丢弃）。
     *
     * 这里返回 ROLLBACK 而不是 UNKNOWN 的前提是：回查发生在事务提交之后，
     * 提交了就必定能查到流水。若担心"事务已提交但回查时主从延迟看不到"，
     * 应当把回查打到主库（本项目单机无从库，不涉及）。
     */
    public boolean checkLocalTransaction(String bizNo) {
        boolean exists = walletRepository.ledgerExists(bizNo);
        log.info("事务消息回查 bizNo={} 流水存在={}", bizNo, exists);
        return exists;
    }

    /** Spring 关闭时释放 Producer（@Bean 的 destroyMethod 找不到接口上的 close，
     *  所以让 adapter 实现 AutoCloseable，由容器自动回调，实测踩过） */
    @Override
    public void close() {
        if (producer != null) {
            try {
                producer.close();
            } catch (Exception e) {
                log.warn("关闭事务消息 Producer 失败", e);
            }
        }
    }

    private String toJson(FundEvent e) {
        return String.format(
                "{\"bizNo\":\"%s\",\"type\":\"%s\",\"errandId\":%d,\"publisherId\":%d,"
                        + "\"runnerId\":%d,\"amountCents\":%d,\"commissionCents\":%d}",
                e.bizNo(), e.type(), e.errandId(), e.publisherId(),
                e.runnerId(), e.amountCents(), e.commissionCents());
    }
}
