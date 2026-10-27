package com.campusdash.domain.voucher.ports;

import com.campusdash.domain.voucher.model.Voucher;
import com.campusdash.domain.voucher.model.VoucherStock;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface VoucherRepository {

    /** 全部类型的库存（含描述），按 type 升序 */
    List<VoucherStock> findAllStocks();

    Optional<VoucherStock> findStock(int type);

    /** 启动时校准描述文案（SQL 种子容易受客户端字符集影响写成乱码） */
    int updateDescription(int type, String description);

    /** 库存行不存在时补一条（不改已有库存） */
    void insertStockIfAbsent(int type, int stock, String description);

    /** 原子扣减库存，返回影响行数；0 表示已抢光 */
    int decrementStock(int type);

    void insert(Voucher voucher);

    Optional<Voucher> findById(long voucherId);

    /** 某用户某类型当前可用的券数量（用于限制同类型只能持有一张） */
    int countAvailable(String userId, int type, Instant now);

    List<Voucher> listByUser(String userId, Instant now);

    /** 把 available 改成 expired；返回影响行数（0 表示已被使用或已过期，幂等） */
    int markExpired(long voucherId);

    int markUsed(long voucherId);
}
