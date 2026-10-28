package com.campusdash.application.usecase;

import com.campusdash.domain.voucher.model.VoucherStock;
import com.campusdash.domain.voucher.ports.VoucherClaimPort;
import com.campusdash.domain.voucher.ports.VoucherClaimPublisher;
import com.campusdash.domain.voucher.ports.VoucherRepository;
import com.campusdash.shared.BizException;
import com.campusdash.shared.ErrorCode;
import com.campusdash.shared.SnowflakeIdGenerator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * 高并发领取：Redis Lua 预扣 → MQ 投递 → worker 落库扣库存。
 * 未启用 MQ 时自动回退到同步 ClaimVoucherUseCase，保证测试环境可用。
 */
@Service
public class ClaimVoucherAsyncUseCase {

    private final VoucherRepository vouchers;
    private final VoucherClaimPort cache;
    private final ObjectProvider<VoucherClaimPublisher> publisher;
    private final SnowflakeIdGenerator idGenerator;
    private final long expireHours;
    private final ClaimVoucherUseCase fallback;

    public ClaimVoucherAsyncUseCase(VoucherRepository vouchers, VoucherClaimPort cache,
                                    ObjectProvider<VoucherClaimPublisher> publisher,
                                    SnowflakeIdGenerator idGenerator,
                                    @Value("${dash.voucher.expire-hours:24}") long expireHours,
                                    ClaimVoucherUseCase fallback) {
        this.vouchers = vouchers;
        this.cache = cache;
        this.publisher = publisher;
        this.idGenerator = idGenerator;
        this.expireHours = expireHours;
        this.fallback = fallback;
    }

    public ClaimVoucherUseCase.Claimed claim(String userId, int typeCode) {
        VoucherClaimPublisher sender = publisher.getIfAvailable();
        if (sender == null) {
            return fallback.claim(userId, typeCode);
        }
        VoucherStock stock = vouchers.findStock(typeCode)
                .orElseThrow(() -> new BizException(ErrorCode.VOUCHER_INVALID, "type=" + typeCode));
        if (!stock.online()) {
            throw new BizException(ErrorCode.VOUCHER_NOT_AVAILABLE, "该优惠券已下架");
        }
        // Redis 的 claimed 键可能因 worker 异常/过期而缺失；DB 才是最终事实。
        if (vouchers.countAvailable(userId, typeCode, Instant.now()) > 0) {
            throw new BizException(ErrorCode.VOUCHER_ALREADY_CLAIMED, "type=" + typeCode);
        }

        long voucherId = idGenerator.nextId();
        Instant expireAt = Instant.now().plus(Duration.ofHours(expireHours));
        VoucherClaimPort.ReserveResult result = cache.reserve(typeCode, userId, voucherId, expireAt);
        if (result == VoucherClaimPort.ReserveResult.ALREADY_CLAIMED) {
            throw new BizException(ErrorCode.VOUCHER_ALREADY_CLAIMED, "type=" + typeCode);
        }
        if (result == VoucherClaimPort.ReserveResult.SOLD_OUT) {
            throw new BizException(ErrorCode.VOUCHER_SOLD_OUT, "type=" + typeCode);
        }

        try {
            sender.publish(voucherId, typeCode, userId, expireAt.toString(), stock.description());
        } catch (RuntimeException e) {
            // MQ 发送失败：立即回滚 Redis 预扣，不把名额挂死
            cache.rollback(typeCode, userId, voucherId);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "领取消息发送失败，请稍后重试");
        }

        int remaining = Math.max(0, stock.stock() - 1);
        return new ClaimVoucherUseCase.Claimed(voucherId, typeCode, stock.description(), expireAt, remaining);
    }
}
