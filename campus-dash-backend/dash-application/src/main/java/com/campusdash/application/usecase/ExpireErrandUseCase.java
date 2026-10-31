package com.campusdash.application.usecase;

import com.campusdash.domain.errand.model.Errand;
import com.campusdash.domain.errand.model.ErrandStatus;
import com.campusdash.domain.errand.ports.ErrandCachePort;
import com.campusdash.domain.notify.ports.NotificationRepository;
import com.campusdash.domain.notify.ports.RealtimeNotifier;
import com.campusdash.domain.errand.ports.ErrandRepository;
import com.campusdash.domain.voucher.ports.VoucherRepository;
import com.campusdash.domain.wallet.model.AccountType;
import com.campusdash.domain.wallet.model.EscrowOrder;
import com.campusdash.domain.wallet.model.LedgerEntry;
import com.campusdash.domain.wallet.model.WalletAccount;
import com.campusdash.domain.wallet.ports.FundAuditPort;
import com.campusdash.domain.wallet.ports.WalletRepository;
import com.campusdash.shared.Money;
import com.campusdash.shared.SnowflakeIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * 任务过期：到点仍无人接单，PUBLISHED -> EXPIRED，并把钱和券退给发单人。
 *
 * 三条防线：
 * 1. 领域状态机 transitTo：非法流转直接抛异常；
 * 2. SQL 带 where status='PUBLISHED' and version=?：并发下只有一次能改成功；
 * 3. 托管单 CAS：HELD -> REFUNDED，防止重复退款。
 *
 * 退款与退券必须和状态变更在同一个事务里：
 * 否则会出现"任务过期了但钱没退"或"钱退了但券还锁着"。
 */
@Service
public class ExpireErrandUseCase {

    private static final Logger log = LoggerFactory.getLogger(ExpireErrandUseCase.class);
    private static final String ESCROW_ACCOUNT_OWNER = "-1";
    /** 站内消息类型：前端按这个值展示图标与筛选 */
    public static final String NOTIFICATION_TYPE = "EXPIRED";

    private final ErrandRepository errandRepository;
    private final WalletRepository walletRepository;
    private final VoucherRepository voucherRepository;
    private final FundAuditPort auditPort;
    private final SnowflakeIdGenerator idGenerator;
    private final ErrandCachePort cachePort;
    private final NotificationRepository notificationRepository;
    private final RealtimeNotifier notifier;

    public ExpireErrandUseCase(ErrandRepository errandRepository,
                               WalletRepository walletRepository,
                               VoucherRepository voucherRepository,
                               FundAuditPort auditPort,
                               SnowflakeIdGenerator idGenerator,
                               ErrandCachePort cachePort,
                               NotificationRepository notificationRepository,
                               RealtimeNotifier notifier) {
        this.notificationRepository = notificationRepository;
        this.notifier = notifier;
        this.errandRepository = errandRepository;
        this.walletRepository = walletRepository;
        this.voucherRepository = voucherRepository;
        this.auditPort = auditPort;
        this.idGenerator = idGenerator;
        this.cachePort = cachePort;
    }

    /**
     * 处理一条过期消息：状态改 EXPIRED + 退款 + 退券。
     *
     * @return EXPIRED 成功过期并已退款 / SKIPPED 无需处理
     */
    @Transactional
    public Outcome expire(long errandId) {
        Optional<Errand> found = errandRepository.findById(errandId);
        if (found.isEmpty()) {
            return Outcome.SKIPPED;
        }
        Errand errand = found.get();

        if (errand.status() != ErrandStatus.PUBLISHED) {
            log.debug("过期消息跳过：状态已是 {} errandId={}", errand.status(), errandId);
            return Outcome.SKIPPED;
        }
        if (errand.expiredAt() == null) {
            return Outcome.SKIPPED;
        }
        if (errand.expiredAt().isAfter(Instant.now())) {
            log.debug("过期消息早到，跳过 errandId={} expiredAt={}", errandId, errand.expiredAt());
            return Outcome.SKIPPED;
        }

        // transito 内部会自增版本，CAS 必须用自增前的版本号
        long expectedVersion = errand.version();
        errand.transitTo(ErrandStatus.EXPIRED, expectedVersion);

        int updated = errandRepository.casExpire(errandId, expectedVersion);
        if (updated == 0) {
            log.info("过期未生效（并发抢单或重复消费）errandId={}", errandId);
            return Outcome.SKIPPED;
        }

        errandRepository.appendStatusLog(errandId, ErrandStatus.PUBLISHED, ErrandStatus.EXPIRED,
                errand.round(), Errand.SYSTEM_OPERATOR);

        // 退款 + 退券：顺序不能变，先把钱退回去，再释放券
        long refundedCents = refundEscrow(errand);
        // 券退回：locked/used -> available（已过期的券直接置 expired），没有券时是 no-op
        int restored = voucherRepository.restoreByErrand(errandId);
        if (restored > 0) {
            log.info("任务过期，优惠券已退回 errandId={} count={}", errandId, restored);
        }

        notifyExpired(errand, refundedCents, restored > 0);

        cachePort.evict(errandId);
        log.info("任务已过期并已退款 errandId={} expiredAt={} refundedCents={} voucherRestored={}",
                errandId, errand.expiredAt(), refundedCents, restored > 0);
        return Outcome.EXPIRED;
    }

    /**
     * 托管金额退回发单人。
     *
     * @return 实际退回的金额（分）；没有托管单或已处理过时返回 0
     */
    private long refundEscrow(Errand errand) {
        long errandId = errand.id();
        Optional<EscrowOrder> found = walletRepository.findEscrowByErrandId(errand.campusId(), errandId);
        if (found.isEmpty()) {
            log.warn("过期任务找不到托管单，跳过退款 errandId={}", errandId);
            return 0L;
        }
        EscrowOrder escrow = found.get();
        if (escrow.status() != EscrowOrder.EscrowStatus.HELD) {
            log.info("托管单不是 HELD，跳过退款 errandId={} status={}", errandId, escrow.status());
            return 0L;
        }

        // CAS 防止重复退款：并发或重复消费时只有一个能把 HELD 改成 REFUNDED
        int escrowed = walletRepository.casEscrowStatus(errand.campusId(), errandId,
                EscrowOrder.EscrowStatus.HELD, EscrowOrder.EscrowStatus.REFUNDED);
        if (escrowed == 0) {
            log.info("托管单已被处理，跳过退款 errandId={}", errandId);
            return 0L;
        }

        Money amount = escrow.amount();
        String bizNo = LedgerEntry.refundBizNo(errandId);
        WalletAccount escrowAccount = walletRepository
                .findByOwner(ESCROW_ACCOUNT_OWNER, AccountType.ESCROW).orElseThrow();
        WalletAccount publisherAccount = walletRepository
                .findByOwner(escrow.publisherId(), AccountType.USER).orElseThrow();

        walletRepository.casDebit(escrowAccount.id(), amount);
        walletRepository.casCredit(publisherAccount.id(), amount);

        walletRepository.insertLedger(new LedgerEntry(
                idGenerator.nextId(), bizNo, escrowAccount.id(), escrow.publisherId(),
                LedgerEntry.Direction.DEBIT, amount,
                escrowAccount.available().minus(amount),
                LedgerEntry.RefType.REFUND, errandId));
        walletRepository.insertLedger(new LedgerEntry(
                idGenerator.nextId(), bizNo, publisherAccount.id(), escrow.publisherId(),
                LedgerEntry.Direction.CREDIT, amount,
                publisherAccount.available().plus(amount),
                LedgerEntry.RefType.REFUND, errandId));

        auditPort.record(bizNo, "EXPIRE_REFUND", errandId, Errand.SYSTEM_OPERATOR,
                String.format("{\"amount\":%d}", amount.cents()), true, null);
        log.info("任务过期已退款 errandId={} amount={}", errandId, amount.cents());
        return amount.cents();
    }

    /**
     * 推送"任务截止已退款"消息到发单人的消息中心。
     *
     * 落库失败不应让整个过期事务回滚（钱已经退了），所以这里吞掉异常只记日志；
     * 推送失败同理——消息表已经是事实，前端刷新就能看到。
     */
    private void notifyExpired(Errand errand, long refundedCents, boolean voucherRestored) {
        long errandId = errand.id();
        String content = buildExpireContent(refundedCents, voucherRestored);
        try {
            // msgKey 带上 errandId：同一个任务只会过期一次，天然幂等
            notificationRepository.insertIfAbsent(idGenerator.nextId(),
                    "expire:" + errandId, errand.publisherId(), errandId,
                    NOTIFICATION_TYPE, content);
        } catch (RuntimeException e) {
            log.warn("过期通知落库失败 errandId={}", errandId, e);
        }
        try {
            notifier.notificationArrived(errand.publisherId(), errandId, NOTIFICATION_TYPE, content);
        } catch (RuntimeException e) {
            log.warn("过期通知推送失败 errandId={}", errandId, e);
        }
    }

    /** 文案要能自解释：钱退了多少、券有没有回来，用户不用去别处核对 */
    private static String buildExpireContent(long refundedCents, boolean voucherRestored) {
        if (refundedCents <= 0) {
            return voucherRestored
                    ? "任务已到截止时间，无人接单已过期，使用的优惠券已退回你的账户"
                    : "任务已到截止时间，无人接单已过期";
        }
        String money = String.format("%.2f 元", refundedCents / 100.0);
        return voucherRestored
                ? String.format("任务已到截止时间，无人接单已过期，%s已退回你的账户，使用的优惠券已退回", money)
                : String.format("任务已到截止时间，无人接单已过期，%s已退回你的账户", money);
    }

    public enum Outcome {
        EXPIRED, SKIPPED
    }
}