package com.campusdash.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Redis 与 Lua 脚本装配。
 *
 * 用 StringRedisTemplate 而不是默认的 RedisTemplate：默认模板用 JDK 序列化，
 * 写进去的 key/value 带二进制前缀，用 redis-cli 根本看不懂，排障非常痛苦。
 * 抢单场景的值就是数字和 ID，字符串序列化最直观。
 *
 * 脚本用 RedisScript 包装后由 Spring 自动走 EVALSHA，
 * 只在 Redis 返回 NOSCRIPT 时才回退到 EVAL 重新加载，省掉每次传输脚本体的开销。
 */
@Configuration
public class RedisLuaConfig {

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    @Bean
    public RedisScript<Long> grabScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/grab.lua"));
        script.setResultType(Long.class);
        return script;
    }

    @Bean
    public RedisScript<Long> rollbackSlotScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/rollback_slot.lua"));
        script.setResultType(Long.class);
        return script;
    }
}
