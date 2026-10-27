package com.campusdash.presentation;

import com.campusdash.application.usecase.ClaimVoucherUseCase;
import com.campusdash.application.usecase.query.QueryVoucherUseCase;
import com.campusdash.domain.voucher.model.Voucher;
import com.campusdash.domain.voucher.model.VoucherType;
import com.campusdash.presentation.auth.CurrentUser;
import com.campusdash.shared.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 惊喜优惠：库存列表、领取、我的券 */
@RestController
@RequestMapping("/api/vouchers")
public class VoucherController {

    private final QueryVoucherUseCase queryUseCase;
    private final ClaimVoucherUseCase claimUseCase;

    public VoucherController(QueryVoucherUseCase queryUseCase, ClaimVoucherUseCase claimUseCase) {
        this.queryUseCase = queryUseCase;
        this.claimUseCase = claimUseCase;
    }

    @GetMapping
    public Result<Map<String, Object>> stocks() {
        List<Map<String, Object>> items = queryUseCase.stocks().stream()
                .map(stock -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("type", stock.type());
                    item.put("stock", stock.stock());
                    item.put("description", stock.description());
                    VoucherType.of(stock.type()).ifPresent(type -> {
                        item.put("thresholdCents", type.thresholdCents());
                        item.put("discountCents", type.discountCents());
                    });
                    item.put("lastChangeTime", stock.lastChangeTime() == null ? "" : stock.lastChangeTime().toString());
                    return item;
                })
                .toList();
        return Result.ok(Map.of("items", items));
    }

    @PostMapping("/{type}/claim")
    public Result<Map<String, Object>> claim(@PathVariable int type) {
        var claimed = claimUseCase.claim(CurrentUser.get(), type);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("voucherId", String.valueOf(claimed.voucherId()));
        data.put("type", claimed.type());
        data.put("description", claimed.description());
        data.put("expireTime", claimed.expireTime().toString());
        data.put("remainingStock", claimed.remainingStock());
        return Result.ok(data);
    }

    @GetMapping("/mine")
    public Result<List<Map<String, Object>>> mine() {
        String userId = CurrentUser.get();
        List<Map<String, Object>> items = queryUseCase.mine(userId).stream()
                .map(voucher -> toView(voucher))
                .toList();
        return Result.ok(items);
    }

    private Map<String, Object> toView(Voucher voucher) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("voucherId", String.valueOf(voucher.voucherId()));
        item.put("type", voucher.type());
        item.put("description", VoucherType.of(voucher.type()).map(VoucherType::description).orElse(""));
        item.put("expireTime", voucher.expireTime().toString());
        item.put("status", voucher.status().dbValue());
        return item;
    }
}
