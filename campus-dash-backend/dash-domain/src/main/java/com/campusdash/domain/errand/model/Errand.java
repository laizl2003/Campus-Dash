package com.campusdash.domain.errand.model;

import com.campusdash.shared.BizException;
import com.campusdash.shared.ErrorCode;
import com.campusdash.shared.Money;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 任务聚合根。
 *
 * 设计要点：状态字段没有 public setter，所有状态变更必须走 transitTo()，
 * 于是"必须校验流转合法性""必须带 version"这两条约束在编译期就被固定住，
 * 新人也没法绕过状态机直接改状态。
 */
public class Errand {

    private final long id;
    private final long campusId;
    private final long publisherId;
    private final ErrandType type;
    private final String title;
    private final Money reward;
    private final int slotTotal;

    private Long grabberId;
    private ErrandStatus status;
    private int slotTaken;
    private int round;
    private long version;
    private Instant lockedAt;
    private Instant deliveredAt;

    /** 系统操作者标识：自动结算、超时流转等无人工触发的动作用它 */
    public static final long SYSTEM_OPERATOR = -1L;

    private final List<ErrandStatusChanged> changes = new ArrayList<>();

    private Errand(long id, long campusId, long publisherId, ErrandType type, String title,
                   Money reward, int slotTotal, Long grabberId, ErrandStatus status,
                   int slotTaken, int round, long version, Instant lockedAt, Instant deliveredAt) {
        this.id = id;
        this.campusId = campusId;
        this.publisherId = publisherId;
        this.type = type;
        this.title = title;
        this.reward = reward;
        this.slotTotal = slotTotal;
        this.grabberId = grabberId;
        this.status = status;
        this.slotTaken = slotTaken;
        this.round = round;
        this.version = version;
        this.lockedAt = lockedAt;
        this.deliveredAt = deliveredAt;
    }

    /** 新建草稿：此时还没托管资金，所以不能是 PUBLISHED */
    public static Errand draft(long id, long campusId, long publisherId, ErrandType type,
                               String title, Money reward, int slotTotal) {
        if (reward.isZero()) {
            throw new IllegalArgumentException("悬赏金额必须大于 0");
        }
        if (slotTotal < 1) {
            throw new IllegalArgumentException("名额数必须 >= 1");
        }
        return new Errand(id, campusId, publisherId, type, title, reward, slotTotal,
                null, ErrandStatus.DRAFT, 0, 0, 0L, null, null);
    }

    /** 从存储重建聚合，不做业务校验（数据已经是既成事实） */
    public static Errand rehydrate(long id, long campusId, long publisherId, ErrandType type,
                                   String title, Money reward, int slotTotal, Long grabberId,
                                   ErrandStatus status, int slotTaken, int round, long version,
                                   Instant lockedAt) {
        return new Errand(id, campusId, publisherId, type, title, reward, slotTotal, grabberId,
                status, slotTaken, round, version, lockedAt, null);
    }

    /** 从存储重建（带送达时间），自动结算扫描需要 deliveredAt */
    public static Errand rehydrate(long id, long campusId, long publisherId, ErrandType type,
                                   String title, Money reward, int slotTotal, Long grabberId,
                                   ErrandStatus status, int slotTaken, int round, long version,
                                   Instant lockedAt, Instant deliveredAt) {
        return new Errand(id, campusId, publisherId, type, title, reward, slotTotal, grabberId,
                status, slotTaken, round, version, lockedAt, deliveredAt);
    }

    /**
     * 状态流转的唯一入口。
     *
     * @param expectedVersion 调用方读到的版本号，用于乐观锁校验。
     *        领域层先做一次内存校验尽早失败，真正的并发裁决仍在数据库的 CAS SQL。
     */
    public void transitTo(ErrandStatus target, long expectedVersion) {
        if (this.version != expectedVersion) {
            throw new BizException(ErrorCode.STALE_VERSION,
                    "errandId=" + id + " expected=" + expectedVersion + " actual=" + version);
        }
        if (!this.status.canTransitTo(target)) {
            throw new BizException(ErrorCode.ILLEGAL_STATE_TRANSITION, this.status + " -> " + target);
        }
        ErrandStatus from = this.status;
        this.status = target;
        this.version++;
        this.changes.add(new ErrandStatusChanged(id, from, target, round));
    }

    /** 托管成功后发布：DRAFT -> PUBLISHED */
    public void publish(long expectedVersion) {
        transitTo(ErrandStatus.PUBLISHED, expectedVersion);
    }

    /**
     * 抢中：PUBLISHED -> LOCKED。
     * 名额与并发裁决由 Redis Lua + DB CAS 完成，这里只维护聚合内的一致性。
     */
    public void lockBy(long runnerId, long expectedVersion, Instant now) {
        if (!status.grabbable()) {
            throw new BizException(ErrorCode.ERRAND_NOT_GRABBABLE, "status=" + status);
        }
        if (slotTaken >= slotTotal) {
            throw new BizException(ErrorCode.SLOT_FULL, "slotTaken=" + slotTaken + " slotTotal=" + slotTotal);
        }
        transitTo(ErrandStatus.LOCKED, expectedVersion);
        this.grabberId = runnerId;
        this.slotTaken++;
        this.lockedAt = now;
    }

    /**
     * 抢中者确认接单：LOCKED -> ACCEPTED。
     * 确认成功后这一轮的超时消息就作废了——靠 version 递增让消息的 CAS 失败。
     */
    public void acceptByRunner(long runnerId, long expectedVersion) {
        if (this.grabberId == null || this.grabberId != runnerId) {
            throw new BizException(ErrorCode.NOT_CURRENT_GRABBER,
                    "errandId=" + id + " grabber=" + grabberId + " actor=" + runnerId);
        }
        transitTo(ErrandStatus.ACCEPTED, expectedVersion);
    }

    /**
     * 确认超时，流转给候选队列的下一位：LOCKED -> LOCKED（合法自环）。
     *
     * round 自增是幂等三件套的第二件：消息带着自己的 round 来，
     * 与当前 round 不符就说明它是上一轮的旧消息，直接丢弃。
     * 单靠 version CAS 无法区分"消息是第几轮发出的"，A→B→A 轮回时会误判。
     *
     * slotTaken 不变：名额还是那一个，只是占用者换人了。
     */
    public void transferToNextRunner(long nextRunnerId, long expectedVersion, Instant now) {
        if (this.status != ErrandStatus.LOCKED) {
            throw new BizException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                    "只有 LOCKED 才能流转，当前=" + status);
        }
        transitTo(ErrandStatus.LOCKED, expectedVersion);
        this.grabberId = nextRunnerId;
        this.round++;
        this.lockedAt = now;
    }

    /**
     * 候选队列已空，任务重新开放：LOCKED -> PUBLISHED。
     * 名额要还回去（slotTaken-1），否则任务显示可抢但实际名额已满。
     */
    public void revertToPublished(long expectedVersion) {
        if (this.status != ErrandStatus.LOCKED) {
            throw new BizException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                    "只有 LOCKED 才能回退，当前=" + status);
        }
        transitTo(ErrandStatus.PUBLISHED, expectedVersion);
        this.grabberId = null;
        this.slotTaken = Math.max(0, this.slotTaken - 1);
        this.round++;
        this.lockedAt = null;
    }

    /** 跑腿取货：ACCEPTED -> PICKED_UP。只有当前跑腿本人能操作 */
    public void pickUp(long runnerId, long expectedVersion) {
        requireCurrentRunner(runnerId);
        transitTo(ErrandStatus.PICKED_UP, expectedVersion);
    }

    /**
     * 跑腿送达：PICKED_UP -> DELIVERED。
     * 送达后进入"等发单人确认"窗口，超过 autoSettleSeconds 未确认则自动结算——
     * 否则跑腿的钱会被无限期拖着，这是平台必须给跑腿的保障。
     */
    public void deliver(long runnerId, long expectedVersion, Instant now) {
        requireCurrentRunner(runnerId);
        transitTo(ErrandStatus.DELIVERED, expectedVersion);
        this.deliveredAt = now;
    }

    /**
     * 结算：DELIVERED -> SETTLED（发单人确认完成，或自动结算）。
     * operatorId 为 SYSTEM_OPERATOR 时表示自动结算，不校验身份。
     */
    public void settle(long operatorId, long expectedVersion) {
        if (operatorId != SYSTEM_OPERATOR && operatorId != publisherId) {
            throw new BizException(ErrorCode.NOT_PUBLISHER,
                    "errandId=" + id + " publisher=" + publisherId + " actor=" + operatorId);
        }
        transitTo(ErrandStatus.SETTLED, expectedVersion);
    }

    /**
     * 发单人取消：DRAFT/PUBLISHED -> CANCELLED，触发退款。
     * 已经有人抢中（LOCKED 及之后）就不能单方面取消了，只能走争议仲裁——
     * 否则跑腿已经在跑了却被无偿取消。
     */
    public void cancelByPublisher(long operatorId, long expectedVersion) {
        if (operatorId != publisherId) {
            throw new BizException(ErrorCode.NOT_PUBLISHER,
                    "errandId=" + id + " publisher=" + publisherId + " actor=" + operatorId);
        }
        if (status != ErrandStatus.DRAFT && status != ErrandStatus.PUBLISHED) {
            throw new BizException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                    "已有跑腿接单，不能直接取消，请发起争议。当前=" + status);
        }
        transitTo(ErrandStatus.CANCELLED, expectedVersion);
        this.slotTaken = 0;
    }

    /** 发起争议：ACCEPTED/PICKED_UP/DELIVERED -> DISPUTED。双方都可发起，托管资金原地不动 */
    public void raiseDispute(long operatorId, long expectedVersion) {
        boolean isParty = operatorId == publisherId
                || (grabberId != null && grabberId == operatorId);
        if (!isParty) {
            throw new BizException(ErrorCode.NOT_CURRENT_GRABBER,
                    "只有发单人或当前跑腿能发起争议，actor=" + operatorId);
        }
        transitTo(ErrandStatus.DISPUTED, expectedVersion);
    }

    /** 仲裁支持跑腿：DISPUTED -> SETTLED */
    public void arbitrateToRunner(long expectedVersion) {
        requireDisputed();
        transitTo(ErrandStatus.SETTLED, expectedVersion);
    }

    /** 仲裁支持发单人：DISPUTED -> REFUNDED */
    public void arbitrateToPublisher(long expectedVersion) {
        requireDisputed();
        transitTo(ErrandStatus.REFUNDED, expectedVersion);
    }

    private void requireDisputed() {
        if (status != ErrandStatus.DISPUTED) {
            throw new BizException(ErrorCode.NOT_ARBITRABLE, "status=" + status);
        }
    }

    private void requireCurrentRunner(long runnerId) {
        if (this.grabberId == null || this.grabberId != runnerId) {
            throw new BizException(ErrorCode.NOT_CURRENT_GRABBER,
                    "errandId=" + id + " grabber=" + grabberId + " actor=" + runnerId);
        }
    }

    /** 判断是否已过自动结算窗口，供兜底扫描使用 */
    public boolean autoSettleDue(Instant now, long autoSettleSeconds) {
        return status == ErrandStatus.DELIVERED
                && deliveredAt != null
                && deliveredAt.plusSeconds(autoSettleSeconds).isBefore(now);
    }

    /** 判断本轮是否已确认超时，供兜底扫描使用 */
    public boolean confirmTimeout(Instant now, long timeoutSeconds) {
        return status == ErrandStatus.LOCKED
                && lockedAt != null
                && lockedAt.plusSeconds(timeoutSeconds).isBefore(now);
    }

    public boolean slotAvailable() {
        return slotTaken < slotTotal;
    }

    public long id() { return id; }
    public long campusId() { return campusId; }
    public long publisherId() { return publisherId; }
    public ErrandType type() { return type; }
    public String title() { return title; }
    public Money reward() { return reward; }
    public int slotTotal() { return slotTotal; }
    public int slotTaken() { return slotTaken; }
    public Long grabberId() { return grabberId; }
    public ErrandStatus status() { return status; }
    public int round() { return round; }
    public long version() { return version; }
    public Instant lockedAt() { return lockedAt; }
    public Instant deliveredAt() { return deliveredAt; }
    public List<ErrandStatusChanged> changes() { return List.copyOf(changes); }

    /** 领域事件：状态流转记录，落 errand_status_log 做事件溯源 */
    public record ErrandStatusChanged(long errandId, ErrandStatus from, ErrandStatus to, int round) {}
}
