package com.campusdash.domain.voucher.ports;

import com.campusdash.domain.voucher.model.Voucher;
import com.campusdash.domain.voucher.model.VoucherStock;
import com.campusdash.domain.voucher.model.VoucherUse;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface VoucherRepository {

    /** 全部类型的库存（含描述），按 type 升序 */
    List<VoucherStock> findAllStocks();

    Optional<VoucherStock> findStock(int type);

    /** 启动时校准描述文案（SQL 种子容易受客户端字符集影响写成乱码） */
    int updateDescription(int type, String description);

    /** 管理端新增一种券：type 由调用方生成（max+1） */
    void insertStock(VoucherStock stock);

    int updateStock(int type, int stock);

    int updateStatus(int type, String status);

    int deleteStock(int type);

    /** 删除某类型的所有领取记录（管理端删除券时调用） */
    int deleteRecordsByType(int type);

    /** 当前最大 type + 1 */
    int nextType();

    /** 原子扣减库存，返回影响行数；0 表示已抢光 */
    int decrementStock(int type);

    void insert(Voucher voucher);

    /** 幂等插入：voucher_id 已存在返回 0 */
    int insertIfAbsent(Voucher voucher);

    /** 删除领取记录（worker 落库冲突回滚用） */
    int deleteById(long voucherId);

    Optional<Voucher> findById(long voucherId);

    /** 某用户某类型当前可用的券数量（用于限制同类型只能持有一张） */
    int countAvailable(String userId, int type, Instant now);

    List<Voucher> listByUser(String userId, Instant now);

    /** 把 available 改成 expired；返回影响行数（0 表示已被使用或已过期，幂等） */
    int markExpired(long voucherId);

    int markUsed(long voucherId);

    /** 兜底扫描：把已过有效期但仍为 available 的券批量置为 expired，返回影响行数 */
    int expireOverdue(int limit);

    /** 兜底扫描前查出待过期券（带 userId，用于释放一人一单标记） */
    List<Voucher> findExpiredAvailable(int limit);

    /**
     * 发布任务时锁定券：只有"本人 + available + 未过期"才能锁定。
     * 返回 1 表示锁定成功；0 表示已被用掉/过期/不属于本人。
     */
    int lockForErrand(long voucherId, String userId, long errandId, long runnerBearCents);

    /** 按订单查锁定的券（结算/退款用） */
    Optional<VoucherUse> findUseByErrand(long errandId);

    /** 订单取消/退款时把券退回可用（有效期不变） */
    int restoreByErrand(long errandId);

    /** 订单结算完成，把 locked 标记为 used */
    int markUsedByErrand(long errandId);
}
