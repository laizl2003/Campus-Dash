package com.campusdash.infrastructure.cache;

import com.campusdash.domain.voucher.ports.VoucherClaimPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** 优惠券 Redis 库存：Lua 保证扣库存、一人一单、pending 计数原子完成 */
@Component
public class RedisVoucherClaimAdapter implements VoucherClaimPort {

    private static final String STOCK = "voucher:stock:";
    private static final String PENDING_COUNT = "voucher:pending:count:";
    private static final String PENDING = "voucher:pending:id:";
    private static final String CLAIMED = "voucher:claimed:";

    private static final DefaultRedisScript<Long> RESERVE = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[4]) == 1 then return -1 end
            local stock = tonumber(redis.call('GET', KEYS[1]) or '0')
            if stock <= 0 then return -2 end
            redis.call('DECR', KEYS[1])
            redis.call('INCR', KEYS[2])
            redis.call('SET', KEYS[4], '1', 'EX', ARGV[1])
            redis.call('SET', KEYS[3], '1', 'EX', ARGV[1])
            return stock - 1
            """, Long.class);

    private static final DefaultRedisScript<Long> ROLLBACK = new DefaultRedisScript<>("""
            if redis.call('DEL', KEYS[3]) == 0 then return 0 end
            redis.call('INCR', KEYS[1])
            local pending = tonumber(redis.call('GET', KEYS[2]) or '0')
            if pending > 0 then redis.call('DECR', KEYS[2]) end
            redis.call('DEL', KEYS[4])
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> CONFIRM = new DefaultRedisScript<>("""
            if redis.call('DEL', KEYS[2]) == 0 then return 0 end
            local pending = tonumber(redis.call('GET', KEYS[1]) or '0')
            if pending > 0 then redis.call('DECR', KEYS[1]) end
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;

    public RedisVoucherClaimAdapter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public ReserveResult reserve(int type, String userId, long voucherId, Instant expireAt) {
        long ttl = Math.max(60, Duration.between(Instant.now(), expireAt).toSeconds() + 3600);
        Long result = redis.execute(RESERVE,
                List.of(STOCK + type, PENDING_COUNT + type, PENDING + voucherId, CLAIMED + type + ":" + userId),
                String.valueOf(ttl));
        if (result == null) return ReserveResult.SOLD_OUT;
        if (result == -1) return ReserveResult.ALREADY_CLAIMED;
        if (result == -2) return ReserveResult.SOLD_OUT;
        return ReserveResult.RESERVED;
    }

    @Override
    public void rollback(int type, String userId, long voucherId) {
        redis.execute(ROLLBACK,
                List.of(STOCK + type, PENDING_COUNT + type, PENDING + voucherId, CLAIMED + type + ":" + userId));
    }

    @Override
    public void confirm(int type, long voucherId) {
        redis.execute(CONFIRM, List.of(PENDING_COUNT + type, PENDING + voucherId));
    }

    @Override
    public void initialize(int type, int dbStock) {
        redis.opsForValue().setIfAbsent(STOCK + type, String.valueOf(dbStock));
        redis.opsForValue().setIfAbsent(PENDING_COUNT + type, "0");
    }

    @Override
    public void reconcile(int type, int dbStock) {
        int pending = Integer.parseInt(redis.opsForValue().get(PENDING_COUNT + type) == null
                ? "0" : redis.opsForValue().get(PENDING_COUNT + type));
        int expected = Math.max(0, dbStock - pending);
        redis.opsForValue().set(STOCK + type, String.valueOf(expected));
    }

    @Override
    public void syncStock(int type, int dbStock) {
        reconcile(type, dbStock);
    }
}
