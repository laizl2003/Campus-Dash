package com.campusdash.application.usecase;

import com.campusdash.domain.errand.model.Errand;
import com.campusdash.domain.errand.model.ErrandStatus;
import com.campusdash.domain.errand.model.ErrandType;
import com.campusdash.domain.errand.ports.ErrandCachePort;
import com.campusdash.domain.errand.ports.ErrandRepository;
import com.campusdash.domain.grab.ports.GrabSlotPort;
import com.campusdash.domain.voucher.model.Voucher;
import com.campusdash.domain.voucher.model.VoucherStatus;
import com.campusdash.domain.voucher.model.VoucherStock;
import com.campusdash.domain.voucher.ports.VoucherRepository;
import com.campusdash.domain.wallet.model.AccountType;
import com.campusdash.domain.wallet.model.EscrowOrder;
import com.campusdash.domain.wallet.model.LedgerEntry;
import com.campusdash.domain.wallet.model.WalletAccount;
import com.campusdash.domain.wallet.ports.WalletRepository;
import com.campusdash.shared.BizException;
import com.campusdash.shared.ErrorCode;
import com.campusdash.shared.Money;
import com.campusdash.shared.SnowflakeIdGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 发布任务：一个本地事务内完成"用券 → 托管实付 → 任务发布"。
 *
 * 优惠券在**发布时**锁定并抵扣：
 *   - 悬赏仍是 R（跑腿看到的金额不变）；
 *   - 发布者实付 P = R - D，托管账户里只有 P；
 *   - 结算时按"跑腿承担 βD、平台营销户承担 (1-β)D"拆账。
 *
 * 用券失败（不属于本人/已用/过期/未达门槛）直接抛业务异常，整个发布回滚。
 */
@Service
public class PublishErrandUseCase {

    /** 名额键 TTL 给足，避免任务还没被抢完 Redis 键就过期了 */
    private static final long SLOT_TTL_SECONDS = 7 * 24 * 3600;
    private static final String ESCROW_ACCOUNT_OWNER = "-1";

    private final ErrandRepository errandRepository;
    private final WalletRepository walletRepository;
    private final GrabSlotPort grabSlotPort;
    private final SnowflakeIdGenerator idGenerator;
    private final ErrandCachePort cache;
    private final VoucherRepository voucherRepository;
    private final double runnerBearRatio;

    public PublishErrandUseCase(ErrandRepository errandRepository,
                                WalletRepository walletRepository,
                                GrabSlotPort grabSlotPort,
                                SnowflakeIdGenerator idGenerator,
                                ErrandCachePort cache,
                                VoucherRepository voucherRepository,
                                @Value("${dash.voucher.runner-bear-ratio:0.40}") double runnerBearRatio) {
        this.errandRepository = errandRepository;
        this.walletRepository = walletRepository;
        this.grabSlotPort = grabSlotPort;
        this.idGenerator = idGenerator;
        this.cache = cache;
        this.voucherRepository = voucherRepository;
        this.runnerBearRatio = runnerBearRatio;
    }

    public record Command(long campusId, String publisherId, ErrandType type,
                          String title, long rewardCents, int slotTotal, Long voucherId) {

        /** 兼容不用券的调用方（测试、压测、脚本） */
        public Command(long campusId, String publisherId, ErrandType type,
                       String title, long rewardCents, int slotTotal) {
            this(campusId, publisherId, type, title, rewardCents, slotTotal, null);
        }
    }

    public record Result(long errandId, ErrandStatus status, long frozenCents,
                         long discountCents, long paidCents) {
    }

    @Transactional(rollbackFor = Exception.class)
    public Result publish(Command cmd) {
        Money reward = Money.ofCents(cmd.rewardCents());
        long errandId = idGenerator.nextId();

        // 1. 锁定优惠券（如果有），拿到券面减免与跑腿承担部分
        long discountCents = 0L;
        long runnerBearCents = 0L;
        if (cmd.voucherId() != null) {
            Voucher voucher = voucherRepository.findById(cmd.voucherId())
                    .orElseThrow(() -> new BizException(ErrorCode.VOUCHER_INVALID, "id=" + cmd.voucherId()));
            VoucherStock stock = voucherRepository.findStock(voucher.type())
                    .orElseThrow(() -> new BizException(ErrorCode.VOUCHER_INVALID, "type=" + voucher.type()));
            if (!stock.online()) {
                throw new BizException(ErrorCode.VOUCHER_NOT_AVAILABLE, "该优惠券已下架");
            }
            if (!cmd.publisherId().equals(voucher.userId())) {
                throw new BizException(ErrorCode.VOUCHER_NOT_AVAILABLE, "券不属于当前用户");
            }
            if (voucher.status() != VoucherStatus.AVAILABLE
                    || voucher.expireTime().isBefore(Instant.now())) {
                throw new BizException(ErrorCode.VOUCHER_NOT_AVAILABLE, "券不可用或已过期");
            }
            if (stock.thresholdCents() > cmd.rewardCents()) {
                throw new BizException(ErrorCode.VOUCHER_NOT_AVAILABLE,
                        "未达到使用门槛，需满" + stock.thresholdCents() / 100 + "元");
            }
            discountCents = stock.discountCents();
            runnerBearCents = Math.round(discountCents * runnerBearRatio);
            int locked = voucherRepository.lockForErrand(
                    cmd.voucherId(), cmd.publisherId(), errandId, runnerBearCents);
            if (locked != 1) {
                throw new BizException(ErrorCode.VOUCHER_NOT_AVAILABLE, "券已被使用");
            }
        }

        long paidCents = cmd.rewardCents() - discountCents;
        if (paidCents <= 0) {
            // 券不能把整单抵成 0（否则托管 0 元，后续结算没有意义）
            throw new BizException(ErrorCode.INVALID_AMOUNT, "使用优惠券后实付必须大于 0");
        }
        Money paid = Money.ofCents(paidCents);

        WalletAccount publisherAccount = walletRepository
                .findByOwner(cmd.publisherId(), AccountType.USER)
                .orElseThrow(() -> new BizException(ErrorCode.ACCOUNT_NOT_FOUND, "publisher=" + cmd.publisherId()));
        WalletAccount escrowAccount = walletRepository
                .findByOwner(ESCROW_ACCOUNT_OWNER, AccountType.ESCROW)
                .orElseThrow(() -> new BizException(ErrorCode.ACCOUNT_NOT_FOUND, "escrow account missing"));

        // 2. 扣的是"实付"，不是悬赏全额
        int frozen = walletRepository.casDebit(publisherAccount.id(), paid);
        if (frozen == 0) {
            throw new BizException(ErrorCode.INSUFFICIENT_BALANCE,
                    "publisher=" + cmd.publisherId() + " required=" + paid);
        }

        // 3. 复式记账：发单人借记 P，托管户贷记 P
        String bizNo = LedgerEntry.escrowBizNo(errandId);
        walletRepository.insertLedger(new LedgerEntry(
                idGenerator.nextId(), bizNo, publisherAccount.id(), cmd.publisherId(),
                LedgerEntry.Direction.DEBIT, paid,
                publisherAccount.available().minus(paid), LedgerEntry.RefType.ESCROW, errandId));
        walletRepository.insertLedger(new LedgerEntry(
                idGenerator.nextId(), bizNo, escrowAccount.id(), cmd.publisherId(),
                LedgerEntry.Direction.CREDIT, paid,
                escrowAccount.available().plus(paid), LedgerEntry.RefType.ESCROW, errandId));
        walletRepository.casCredit(escrowAccount.id(), paid);

        // 4. 托管单金额 = 实付（结算时据此 + 券面还原出悬赏 R）
        walletRepository.insertEscrow(EscrowOrder.held(
                idGenerator.nextId(), cmd.campusId(), errandId, cmd.publisherId(), paid));

        // 5. 任务落库并发布（悬赏字段仍是 R）
        Errand errand = Errand.draft(errandId, cmd.campusId(), cmd.publisherId(),
                cmd.type(), cmd.title(), reward, cmd.slotTotal());
        errandRepository.insert(errand);

        long versionBeforePublish = errand.version();
        errand.publish(versionBeforePublish);
        int published = errandRepository.casPublish(errandId, versionBeforePublish);
        if (published == 0) {
            throw new BizException(ErrorCode.STALE_VERSION, "publish failed, errandId=" + errandId);
        }
        errandRepository.appendStatusLog(errandId, ErrandStatus.DRAFT, ErrandStatus.PUBLISHED,
                0, cmd.publisherId());

        grabSlotPort.initSlot(errandId, cmd.slotTotal(), SLOT_TTL_SECONDS);
        cache.registerExisting(errandId);

        return new Result(errandId, ErrandStatus.PUBLISHED, paidCents, discountCents, paidCents);
    }
}
