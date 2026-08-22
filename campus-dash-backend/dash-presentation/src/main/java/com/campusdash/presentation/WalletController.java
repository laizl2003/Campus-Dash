package com.campusdash.presentation;

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

    public WalletController(QueryWalletUseCase queryUseCase) {
        this.queryUseCase = queryUseCase;
    }

    @GetMapping
    public Result<Map<String, Object>> balance() {
        long userId = CurrentUser.get();
        var b = queryUseCase.balance(userId);
        return Result.ok(Map.of("availableCents", b.availableCents(), "frozenCents", b.frozenCents()));
    }

    @GetMapping("/ledger")
    public Result<List<Map<String, Object>>> ledger(@RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "20") int size) {
        long userId = CurrentUser.get();
        return Result.ok(queryUseCase.ledger(userId, page, size).stream()
                .map(v -> Map.<String, Object>of(
                        "time", v.time().toString(), "direction", v.direction(),
                        "amountCents", v.amountCents(), "refType", v.refType(),
                        "refId", v.refId(), "bizNo", v.bizNo()))
                .toList());
    }
}
