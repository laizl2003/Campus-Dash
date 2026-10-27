package com.campusdash.presentation;

import com.campusdash.application.usecase.RechargeWalletUseCase;
import com.campusdash.application.usecase.query.QueryWalletUseCase;
import com.campusdash.presentation.auth.CurrentUser;
import com.campusdash.shared.Result;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/wallet")
public class WalletController {

    private final QueryWalletUseCase queryUseCase;
    private final RechargeWalletUseCase rechargeUseCase;

    public WalletController(QueryWalletUseCase queryUseCase, RechargeWalletUseCase rechargeUseCase) {
        this.queryUseCase = queryUseCase;
        this.rechargeUseCase = rechargeUseCase;
    }

    @GetMapping
    public Result<Map<String, Object>> balance() {
        String userId = CurrentUser.get();
        var b = queryUseCase.balance(userId);
        return Result.ok(Map.of("availableCents", b.availableCents(), "frozenCents", b.frozenCents()));
    }

    public record RechargeRequest(Long amountCents) {
    }

    /** 充值（演示）：直接给当前用户账户加余额，同时写一条 RECHARGE 流水 */
    @PostMapping("/recharge")
    public Result<Map<String, Object>> recharge(@RequestBody RechargeRequest req) {
        long amountCents = req == null || req.amountCents() == null ? 0L : req.amountCents();
        var result = rechargeUseCase.recharge(CurrentUser.get(), amountCents);
        return Result.ok(Map.of("availableCents", result.availableCents()));
    }

    @GetMapping("/ledger")
    public Result<List<Map<String, Object>>> ledger(@RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "20") int size) {
        String userId = CurrentUser.get();
        return Result.ok(queryUseCase.ledger(userId, page, size).stream()
                .map(v -> Map.<String, Object>of(
                        "time", v.time().toString(), "direction", v.direction(),
                        "amountCents", v.amountCents(), "refType", v.refType(),
                        "refId", v.refId(), "bizNo", v.bizNo()))
                .toList());
    }
}
