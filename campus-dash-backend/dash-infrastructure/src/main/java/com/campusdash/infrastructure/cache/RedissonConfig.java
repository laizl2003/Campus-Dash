package com.campusdash.infrastructure.cache;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 装配。
 *
 * ── 为什么与 Lettuce 并存而不是二选一 ──
 * Lettuce（spring-data-redis）负责所有普通读写与 Lua 脚本（抢单链路）；
 * Redisson 只负责两件 Lettuce 做不了的事：布隆过滤器与可重入分布式锁。
 * 两个客户端各自连接池，连接数分开配——叠加打满 Redis 是很现实的风险，
 * 所以 Redisson 只给 16 个连接（它的调用量远小于业务读写）。
 *
 * P1 时刻意没引 Redisson（抢单不需要分布式锁，靠 Lua + DB CAS），
 * 到 P5 才因为布隆与重建锁引入——依赖是被真实需求拉进来的，不是一开始就堆上。
 */
@Configuration
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(
            @Value("${spring.data.redis.host:127.0.0.1}") String host,
            @Value("${spring.data.redis.port:6380}") int port,
            @Value("${dash.cache.redisson-pool-size:16}") int poolSize) {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setConnectionPoolSize(poolSize)
                .setConnectionMinimumIdleSize(Math.max(2, poolSize / 4))
                .setTimeout(2000);
        return Redisson.create(config);
    }
}
