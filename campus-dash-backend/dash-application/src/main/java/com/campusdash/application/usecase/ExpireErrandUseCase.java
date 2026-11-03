package com.campusdash.application.usecase;

import com.campusdash.domain.credit.model.CreditEvent;
import com.campusdash.domain.credit.model.CreditEventType;
import com.campusdash.domain.credit.ports.CreditRankingPort;
import com.campusdash.domain.credit.ports.CreditRepository;
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

    /**
     * 允许过期作废的状态：PUBLISHED 到 SETTLED 之前。
     *
     * 业务含义：接了单但一直不送达，到点也要作废并退款，
     * 否则钱会永远冻在托管里、跑腿人也不会被追责。
     * SETTLED 之后钱已经结算给跑腿人，不在本用例范围内。
     */
    private static final java.util.Set<ErrandStatus> EXPIRABLE = java.util.EnumSet.of(
            ErrandStatus.PUBLISHED, ErrandStatus.LOCKED, ErrandStatus.ACCEPTED,
            ErrandStatus.PICKED_UP, ErrandStatus.DELIVERED);
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
    private final CreditRepository creditRepository;
    private final CreditRankingPort creditRankingPort;

    public ExpireErrandUseCase(ErrandRepository errandRepository,
                               WalletRepository walletRepository,
                               VoucherRepository voucherRepository,
                               FundAuditPort auditPort,
                               SnowflakeIdGenerator idGenerator,
                               ErrandCachePort cachePort,
                               NotificationRepository notificationRepository,
                               RealtimeNotifier notifier,
                               CreditRepository creditRepository,
                               CreditRankingPort creditRankingPort) {
        this.notificationRepository = notificationRepository;
        this.notifier = notifier;
        this.creditRepository = creditRepository;
        this.creditRankingPort = creditRankingPort;
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

        if (!EXPIRABLE.contains(errand.status())) {
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
        ErrandStatus fromStatus = errand.status();
        long expectedVersion = errand.version();
        errand.transitTo(ErrandStatus.EXPIRED, expectedVersion);

        int updated = errandRepository.casExpire(errandId, expectedVersion);
        if (updated == 0) {
            log.info("过期未生效（并发抢单或重复消费）errandId={}", errandId);
            return Outcome.SKIPPED;
        }

        errandRepository.appendStatusLog(errandId, fromStatus, ErrandStatus.EXPIRED,
                errand.round(), Errand.SYSTEM_OPERATOR);

        // 退款 + 退券：顺序不能变，先把钱退回去，再释放券
        long refundedCents = refundEscrow(errand);
        // 券退回：locked/used -> available（已过期的券直接置 expired），没有券时是 no-op
        int restored = voucherRepository.restoreByErrand(errandId);
        if (restored > 0) {
            log.info("任务过期，优惠券已退回 errandId={} count={}", errandId, restored);
        }

        // 有人接了单却没在截止前送达：跑腿人要扣信用分。
        // bizNo 带 errandId，重复消费时靠唯一索引幂等跳过。
        penalizeGrabber(errand, fromStatus);

        notifyExpired(errand, refundedCents, restored > 0, fromStatus);

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
     * 接了单却超时未送达：扣跑腿人信用分。
     *
     * 为什么在过期用例里做而不是单独一个 job：
     *   "任务作废"和"跑腿人被追责"是同一件事的两面，分开做会出现
     *   "单过期了但没人担责"或"扣了分但单还在"的中状态。
     * 幂等靠 credit_event.biz_no 的唯一索引，与退款 CAS 是同一套思路。
     */
    private void penalizeGrabber(Errand errand, ErrandStatus fromStatus) {
        String grabberId = errand.grabberId();
        if (grabberId == null || grabberId.isBlank()) {
            // 没人接单：只过期退款，不涉及信用
            return;
        }
        try {
            boolean applied = creditRepository.applyEvent(new CreditEvent(
                    idGenerator.nextId(),
                    CreditEvent.expireAfterGrabBizNo(errand.id()),
                    grabberId,
                    CreditEventType.EXPIRE_AFTER_GRAB,
                    CreditEventType.EXPIRE_AFTER_GRAB.delta(),
                    "ERRAND", errand.id(), Instant.now()));
            if (!applied) {
                log.info("信用事件已存在，跳过 errandId={} grabber={}", errand.id(), grabberId);
                return;
            }
            creditRankingPort.update(errand.campusId(), grabberId, creditRepository.scoreOf(grabberId));
            notifier.creditChanged(grabberId, creditRepository.scoreOf(grabberId),
                    CreditEventType.EXPIRE_AFTER_GRAB.delta(), "接单后超时未送达");
            notifyRunner(errand, grabberId);
            log.info("接单超时未送达，已扣信用分 errandId={} grabber={} from={}",
                    errand.id(), grabberId, fromStatus);
        } catch (RuntimeException e) {
            // 扣分失败不能让退款回滚：钱是硬约束，信用分可以补
            log.warn("过期扣信用分失败 errandId={}", errand.id(), e);
        }
    }

    /** 通知跑腿人：这一单已经作废、你被扣分了 */
    private void notifyRunner(Errand errand, String grabberId) {
        int current = creditRepository.scoreOf(grabberId);
        String content = String.format(
                "任务「%s」已到截止时间且未送达，已作废并退款给发单人；你的信用分被扣 %d 分，当前 %d 分",
                errand.title(), -CreditEventType.EXPIRE_AFTER_GRAB.delta(), current);
        try {
            notificationRepository.insertIfAbsent(idGenerator.nextId(),
                    "expire-runner:" + errand.id(), grabberId, errand.id(),
                    NOTIFICATION_TYPE, content);
        } catch (RuntimeException e) {
            log.warn("过期通知(跑腿人)落库失败 errandId={}", errand.id(), e);
        }
        try {
            notifier.notificationArrived(grabberId, errand.id(), NOTIFICATION_TYPE, content);
        } catch (RuntimeException e) {
            log.warn("过期通知(跑腿人)推送失败 errandId={}", errand.id(), e);
        }
    }

    /**
     * 推送"任务截止已退款"消息到发单人的消息中心。
     *
     * 落库失败不应让整个过期事务回滚（钱已经退了），所以这里吞掉异常只记日志；
     * 推送失败同理——消息表已经是事实，前端刷新就能看到。
     */
    private void notifyExpired(Errand errand, long refundedCents, boolean voucherRestored,
                               ErrandStatus fromStatus) {
        long errandId = errand.id();
        boolean wasGrabbed = errand.grabberId() != null && !errand.grabberId().isBlank();
        String content = buildExpireContent(refundedCents, voucherRestored, wasGrabbed);
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

    /** 文案要能自解释：钱退了多少、券有没有回来、是不是有人接了没送达 */
    private static String buildExpireContent(long refundedCents, boolean voucherRestored,
                                             boolean wasGrabbed) {
        String reason = wasGrabbed ? "跑腿超时未送达" : "无人接单";
        if (refundedCents <= 0) {
            return voucherRestored
                    ? String.format("任务已到截止时间（%s）已作废，使用的优惠券已退回你的账户", reason)
                    : String.format("任务已到截止时间（%s）已作废", reason);
        }
        String money = String.format("%.2f 元", refundedCents / 100.0);
        return voucherRestored
                ? String.format("任务已到截止时间（%s）已作废，%s已退回你的账户，使用的优惠券已退回", reason, money)
                : String.format("任务已到截止时间（%s）已作废，%s已退回你的账户", reason, money);
    }

    public enum Outcome {
        EXPIRED, SKIPPED
    }
}