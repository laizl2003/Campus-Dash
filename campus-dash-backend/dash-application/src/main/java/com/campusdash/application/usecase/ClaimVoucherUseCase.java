package com.campusdash.application.usecase;

import com.campusdash.domain.errand.ports.LocalMessageRepository;
import com.campusdash.domain.voucher.model.Voucher;
import com.campusdash.domain.voucher.model.VoucherStatus;
import com.campusdash.domain.voucher.model.VoucherType;
import com.campusdash.domain.voucher.ports.VoucherRepository;
import com.campusdash.shared.BizException;
import com.campusdash.shared.ErrorCode;
import com.campusdash.shared.SnowflakeIdGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * 领取优惠券。
 *
 * 三件事必须在同一个事务里：
 *   1. 扣库存（UPDATE ... WHERE stock > 0，靠影响行数判断是否抢光）
 *   2. 写领取记录（雪花 ID 作为 voucher_id）
 *   3. 登记一条"到期作废"的本地消息（2 天后由 worker 投递到 voucher-expire）
 *
 * 库存扣减不能先查再减：并发下两个请求都会读到 stock=1。
 * 这里用单条带条件的 UPDATE，受影响行数就是并发裁决结果。
 */
@Service
public class ClaimVoucherUseCase {

    /** 到期消息的 topic，worker 侧的消费者订阅它 */
    public static final String EXPIRE_TOPIC = "voucher-expire";

    private final VoucherRepository vouchers;
    private final LocalMessageRepository localMessages;
    private final SnowflakeIdGenerator idGenerator;
    private final long expireHours;

    public ClaimVoucherUseCase(VoucherRepository vouchers,
                               LocalMessageRepository localMessages,
                               SnowflakeIdGenerator idGenerator,
                               @Value("${dash.voucher.expire-hours:48}") long expireHours) {
        this.vouchers = vouchers;
        this.localMessages = localMessages;
        this.idGenerator = idGenerator;
        this.expireHours = expireHours;
    }

    public record Claimed(long voucherId, int type, String description, Instant expireTime, int remainingStock) {
    }

    @Transactional(rollbackFor = Exception.class)
    public Claimed claim(String userId, int typeCode) {
        VoucherType type = VoucherType.of(typeCode)
                .orElseThrow(() -> new BizException(ErrorCode.VOUCHER_INVALID, "type=" + typeCode));

        Instant now = Instant.now();
        // 同一类型的券，一个人只能持有一张可用的（否则库存会被单个用户刷光）
        if (vouchers.countAvailable(userId, typeCode, now) > 0) {
            throw new BizException(ErrorCode.VOUCHER_ALREADY_CLAIMED, "type=" + typeCode);
        }
        if (vouchers.decrementStock(typeCode) != 1) {
            throw new BizException(ErrorCode.VOUCHER_SOLD_OUT, "type=" + typeCode);
        }

        long voucherId = idGenerator.nextId();
        Instant expireAt = now.plus(Duration.ofHours(expireHours));
        vouchers.insert(new Voucher(voucherId, userId, typeCode, expireAt, VoucherStatus.AVAILABLE, null, now));

        // 本地消息表 + 定时投递：到点后 worker 发到 voucher-expire，消费者把状态改成 expired。
        // 这里只登记不发送——和自动结算用的是同一套基础设施。
        localMessages.enqueue(
                idGenerator.nextId(),
                "voucherexpire:" + voucherId,
                EXPIRE_TOPIC,
                "{\"voucherId\":" + voucherId + "}",
                expireAt);

        int remaining = vouchers.findStock(typeCode).map(s -> s.stock()).orElse(0);
        return new Claimed(voucherId, typeCode, type.description(), expireAt, remaining);
    }
}
