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
