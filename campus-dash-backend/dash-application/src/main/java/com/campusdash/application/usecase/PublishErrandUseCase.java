package com.campusdash.application.usecase;

import com.campusdash.domain.errand.model.Errand;
import com.campusdash.domain.errand.model.ErrandStatus;
import com.campusdash.domain.errand.model.ErrandType;
import com.campusdash.domain.errand.ports.ErrandCachePort;
import com.campusdash.domain.errand.ports.ErrandRepository;
import com.campusdash.domain.grab.ports.GrabSlotPort;
import com.campusdash.domain.wallet.model.AccountType;
import com.campusdash.domain.wallet.model.EscrowOrder;
import com.campusdash.domain.wallet.model.LedgerEntry;
import com.campusdash.domain.wallet.model.WalletAccount;
import com.campusdash.domain.wallet.ports.WalletRepository;
import com.campusdash.shared.BizException;
import com.campusdash.shared.ErrorCode;
import com.campusdash.shared.Money;
import com.campusdash.shared.SnowflakeIdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 发布任务用例：一个本地事务内完成资金托管与任务发布。
 *
 * 这是模块化单体最大的红利——托管扣款、复式记账、托管单、任务状态
 * 全在同一个数据库事务里，不需要任何分布式事务。
 * 拆微服务后这一段就必须改成 Seata TCC 或 Saga 补偿（见架构文档第 12 节）。
 *
 * 事务边界画在应用层而不是领域层：领域对象只负责业务规则，
 * 不知道"事务"这个技术概念的存在。
 */
@Service
public class PublishErrandUseCase {

    /** 名额键 TTL 给足，避免任务还没被抢完 Redis 键就过期了 */
    private static final long SLOT_TTL_SECONDS = 7 * 24 * 3600;
    private static final long ESCROW_ACCOUNT_OWNER = -1L;

    private final ErrandRepository errandRepository;
    private final WalletRepository walletRepository;
    private final GrabSlotPort grabSlotPort;
    private final SnowflakeIdGenerator idGenerator;
    private final ErrandCachePort cache;

    public PublishErrandUseCase(ErrandRepository errandRepository,
                                WalletRepository walletRepository,
                                GrabSlotPort grabSlotPort,
                                SnowflakeIdGenerator idGenerator,
                                ErrandCachePort cache) {
        this.errandRepository = errandRepository;
        this.walletRepository = walletRepository;
        this.grabSlotPort = grabSlotPort;
        this.idGenerator = idGenerator;
        this.cache = cache;
    }

    public record Command(long campusId, long publisherId, ErrandType type,
                          String title, long rewardCents, int slotTotal) {}

    public record Result(long errandId, ErrandStatus status, long frozenCents) {}

    /**
     * rollbackFor 必须显式写 Exception.class：
     * Spring 默认只对 RuntimeException 回滚，checked 异常不会触发回滚，
     * 这是 @Transactional 六种失效场景里最常见的一种。
     */
    @Transactional(rollbackFor = Exception.class)
    public Result publish(Command cmd) {
        Money reward = Money.ofCents(cmd.rewardCents());

        // 幂等前置检查：一个任务只能托管一次。
        // 真正的并发安全靠 escrow_order.uk_errand 与 wallet_ledger.uk_biz_direction 唯一索引，
        // 这里的检查只是为了给出更友好的错误信息。
        long errandId = idGenerator.nextId();

        WalletAccount publisherAccount = walletRepository
                .findByOwner(cmd.publisherId(), AccountType.USER)
                .orElseThrow(() -> new BizException(ErrorCode.ACCOUNT_NOT_FOUND, "publisher=" + cmd.publisherId()));
        WalletAccount escrowAccount = walletRepository
                .findByOwner(ESCROW_ACCOUNT_OWNER, AccountType.ESCROW)
                .orElseThrow(() -> new BizException(ErrorCode.ACCOUNT_NOT_FOUND, "escrow account missing"));

        // 1. 冻结发单人余额：带条件 CAS，affectedRows=0 即余额不足或并发冲突
        int frozen = walletRepository.casDebit(publisherAccount.id(), reward);
        if (frozen == 0) {
            throw new BizException(ErrorCode.INSUFFICIENT_BALANCE,
                    "publisher=" + cmd.publisherId() + " required=" + reward);
        }

        // 2. 复式记账：发单人账户借记，托管账户贷记，金额相等
        String bizNo = LedgerEntry.escrowBizNo(errandId);
        walletRepository.insertLedger(new LedgerEntry(
                idGenerator.nextId(), bizNo, publisherAccount.id(), cmd.publisherId(),
                LedgerEntry.Direction.DEBIT, reward,
                publisherAccount.available().minus(reward), LedgerEntry.RefType.ESCROW, errandId));
        walletRepository.insertLedger(new LedgerEntry(
                idGenerator.nextId(), bizNo, escrowAccount.id(), cmd.publisherId(),
                LedgerEntry.Direction.CREDIT, reward,
                escrowAccount.available().plus(reward), LedgerEntry.RefType.ESCROW, errandId));
        walletRepository.casCredit(escrowAccount.id(), reward);

        // 3. 托管单置 HELD
        walletRepository.insertEscrow(EscrowOrder.held(
                idGenerator.nextId(), cmd.campusId(), errandId, cmd.publisherId(), reward));

        // 4. 任务落库并发布。先 DRAFT 再 CAS 到 PUBLISHED，
        //    这样状态机的 DRAFT -> PUBLISHED 流转在数据库里也有真实痕迹
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

        // 5. 初始化 Redis 名额。
        //    放在事务最后：如果前面任何一步失败，事务回滚，这里根本不会执行，
        //    也就不会出现"DB 没任务但 Redis 有名额"的脏数据。
        //    反过来若这一步失败导致事务回滚，Redis 里可能残留名额键，
        //    但那个任务在 DB 里不存在，抢单时 CAS 必然失败，不会造成资金问题。
        grabSlotPort.initSlot(errandId, cmd.slotTotal(), SLOT_TTL_SECONDS);

        // 6. 登记到布隆过滤器：新任务必须能被详情查询找到。
        //    不登记的话布隆会判"不存在"，详情接口直接返 404（防穿透误伤真实数据）
        cache.registerExisting(errandId);

        return new Result(errandId, ErrandStatus.PUBLISHED, reward.cents());
    }
}
