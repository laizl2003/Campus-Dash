package com.campusdash.infrastructure.config;

import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import com.campusdash.domain.wallet.ports.FundEventPort;
import com.campusdash.domain.wallet.ports.WalletRepository;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * RocketMQ 5.x 客户端装配（gRPC proxy 模式）。
 *
 * 用 dash.mq.enabled 开关控制：关闭时由 NoopDelayMessageAdapter 兜底，
 * 应用与集成测试在没有 MQ 的环境下仍能跑通，超时流转退化为纯靠 worker 扫描。
 * 这不只是为了方便——它本身就证明了"主通道 + 兜底"的价值。
 */
@Configuration
@ConditionalOnProperty(name = "dash.mq.enabled", havingValue = "true")
public class RocketMqConfig {

    @Bean
    public ClientServiceProvider rocketMqProvider() {
        return ClientServiceProvider.loadService();
    }

    @Bean
    public ClientConfiguration rocketMqClientConfiguration(
            @Value("${dash.mq.endpoints:127.0.0.1:8081}") String endpoints) {
        return ClientConfiguration.newBuilder()
                .setEndpoints(endpoints)
                .setRequestTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Producer 是线程安全的重量级对象，全局单例复用。
     * 每次发消息都新建 Producer 会导致连接爆炸——这是 MQ 接入最常见的性能错误。
     *
     * 关键：不在 setTopics 里预绑定 topic。
     *
     * 预绑定会让 Producer 在启动时就去 broker 拉取 topic 路由，topic 不存在就直接
     * 抛异常导致整个应用启动失败（实测踩过：重建 broker 容器后 topic 丢失，
     * 应用与所有集成测试全部起不来）。
     * 超时流转有兜底扫描通道，MQ 是可降级的——绝不能让它把整个应用拖死。
     * 不预绑定时路由改为首次发送时惰性获取，发送失败会被 dispatch 捕获，
     * 消息留在 local_message 里由 worker 重发。
     */
    @Bean(destroyMethod = "close")
    public Producer timeoutProducer(ClientServiceProvider provider,
                                    ClientConfiguration configuration) throws ClientException {
        return provider.newProducerBuilder()
                .setClientConfiguration(configuration)
                .build();
    }

    /**
     * 资金事件适配器（事务消息）。
     *
     * 事务消息必须用带 TransactionChecker 的专用 Producer：
     * 5.x API 里 checker 只能在构建时注册（ProducerBuilder.setTransactionChecker），
     * 复用不带 checker 的 timeoutProducer 会在 beginTransaction 时直接抛
     * "Transaction checker should not be null"（线上联调实测踩过）。
     */
    @Bean
    public FundEventPort fundEventPort(ClientServiceProvider provider,
                                       ClientConfiguration configuration,
                                       WalletRepository walletRepository) throws ClientException {
        RocketMqFundEventAdapter adapter =
                new RocketMqFundEventAdapter(null, provider, walletRepository);
        Producer txProducer = provider.newProducerBuilder()
                .setClientConfiguration(configuration)
                .setTransactionChecker(adapter.checker())
                .build();
        adapter.attachProducer(txProducer);
        return adapter;
    }
}
