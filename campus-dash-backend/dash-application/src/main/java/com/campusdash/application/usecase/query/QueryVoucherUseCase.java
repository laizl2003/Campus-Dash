package com.campusdash.application.usecase.query;

import com.campusdash.domain.voucher.model.Voucher;
import com.campusdash.domain.voucher.model.VoucherStock;
import com.campusdash.domain.voucher.ports.VoucherRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/** 优惠券查询：可领取的库存 + 我的券 */
@Service
public class QueryVoucherUseCase {

    private final VoucherRepository vouchers;

    public QueryVoucherUseCase(VoucherRepository vouchers) {
        this.vouchers = vouchers;
    }

    public List<VoucherStock> stocks() {
        return vouchers.findAllStocks();
    }

    public List<Voucher> mine(String userId) {
        return vouchers.listByUser(userId, Instant.now());
    }
}
